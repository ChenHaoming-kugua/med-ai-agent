package org.example.controller;

import org.example.service.MsfxSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/msfx-sync")
public class MsfxSyncController {

    private static final Logger log = LoggerFactory.getLogger(MsfxSyncController.class);
    private final MsfxSyncService service;

    public MsfxSyncController(MsfxSyncService service) { this.service = service; }

    /**
     * 入参示例: { "rows": [ {"name":"...","appkey":"...","refEntId":"...","entId":"..."}, ... ] }
     */
    @PostMapping("/diagnose")
    public Map<String, Object> diagnose(@RequestBody Map<String, Object> body) {
        long t0 = System.currentTimeMillis();
        @SuppressWarnings("unchecked")
        List<Map<String, String>> rows = (List<Map<String, String>>) body.get("rows");
        log.info("==> POST /api/msfx-sync/diagnose rows={}", rows == null ? 0 : rows.size());
        Map<String, Object> result = service.diagnose(rows);
        log.info("<== POST /api/msfx-sync/diagnose cost={}ms summary={}",
            System.currentTimeMillis() - t0, result.get("summary"));
        return result;
    }

    @PostMapping("/retail-diagnosis-sql")
    public Map<String, Object> retailDiagnosisSql(@RequestBody Map<String, Object> body) {
        return buildRetailDiagnosisSql(
            numericOrDefault(body.get("parentAreaCode"), "161"),
            numericOrDefault(body.get("placepointId"), "106016"),
            numericOrBlank(body.get("rsaid")),
            dateOrBlank(body.get("businessDate")),
            numericList(body.get("screenshotDetailIds")),
            numericList(body.get("realDetailIds")),
            numericList(body.get("traceCodes")));
    }

    @GetMapping("/retail-diagnosis-sql")
    public Map<String, Object> retailDiagnosisSqlGet(
            @RequestParam(required = false) String parentAreaCode,
            @RequestParam(required = false) String placepointId,
            @RequestParam(required = false) String rsaid,
            @RequestParam(required = false) String businessDate,
            @RequestParam(required = false) List<String> screenshotDetailIds,
            @RequestParam(required = false) List<String> realDetailIds,
            @RequestParam(required = false) List<String> traceCodes) {
        return buildRetailDiagnosisSql(
            numericOrDefault(parentAreaCode, "161"),
            numericOrDefault(placepointId, "106016"),
            numericOrBlank(rsaid),
            dateOrBlank(businessDate),
            numericList(screenshotDetailIds),
            numericList(realDetailIds),
            numericList(traceCodes));
    }

    private static Map<String, Object> buildRetailDiagnosisSql(String parentAreaCode,
                                                               String placepointId,
                                                               String rsaid,
                                                               String businessDate,
                                                               List<String> screenshotDetailIds,
                                                               List<String> realDetailIds,
                                                               List<String> traceCodes) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("parentAreaCode", parentAreaCode);
        result.put("placepointId", placepointId);
        result.put("businessDate", businessDate);
        result.put("screenshotDetailIds", screenshotDetailIds);
        result.put("realDetailIds", realDetailIds);
        result.put("traceCodes", traceCodes);

        Map<String, String> sql = new LinkedHashMap<>();
        sql.put("01_group_buy_mapping", groupBuyMappingSql(screenshotDetailIds, realDetailIds));
        sql.put("02_real_sale_detail", realSaleDetailSql(realDetailIds, rsaid));
        sql.put("03_bms_ecode_by_screenshot_detail", bmsEcodeByScreenshotDetailSql(screenshotDetailIds, traceCodes));
        sql.put("04_trace_join_check", traceJoinCheckSql(realDetailIds));
        sql.put("05_date_window_check", dateWindowCheckSql(placepointId, realDetailIds, businessDate));
        sql.put("06_full_candidate_check", fullCandidateCheckSql(parentAreaCode, placepointId, realDetailIds, businessDate));
        sql.put("07_success_log_check", successLogCheckSql(placepointId, realDetailIds, rsaid, traceCodes));
        sql.put("08_request_log_check", requestLogCheckSql(rsaid, realDetailIds));
        sql.put("09_abnormal_check", abnormalCheckSql(placepointId, realDetailIds, rsaid, traceCodes));
        result.put("sql", sql);
        return result;
    }

    /**
     * 生成"门店哪些追溯码没传码上放心"的可执行 SQL。
     * 码上放心有两条上传接口（已对齐 medins-databridge-platform 仓内逻辑）：
     *   1. uploadretail   — 零售单上传，成功写 MSFX.ALI_HEALTH_ECODE_SYNC_D
     *   2. uploadinoutbill — 出入库单据上传，成功写 MSFX.ALI_HEALTH_PURCH_ECODE_D
     * 两条链路码源都是 GYGDPOS.BMS_ECODE_RECORD，但 COMEFROM 不同，落表不同，必须分别判定。
     *
     * scene 参数：
     *   - retail  : 只查零售未传（COMEFROM 零售类，落 ALI_HEALTH_ECODE_SYNC_D）
     *   - inout   : 只查出入库未传（COMEFROM 出入库类，落 ALI_HEALTH_PURCH_ECODE_D）
     *   - all     : 两者 UNION，任意一条没传都算未传（默认）
     *
     * 业务规则（用户要求）：
     * - "传过" = 对应上传日志表里存在记录，不限 SYNC_FLAG（成功/失败/驳回都算传过）
     * - "已销售" = ALI_HEALTH_ALREADY_SALE_ECODE 有记录，默认排除（业务红线，仅零售场景适用）
     * - "平台驳回" = ALI_HEALTH_ABNORMAL_ECODE，默认不排除（驳回=传过，仅零售场景）
     * - "预校验异常" = ALI_SYNC_ECODE_ABNORMAL_DATA，默认不排除（=传过，仅零售场景）
     * 出入库场景没有异常/已售表，只看 ALI_HEALTH_PURCH_ECODE_D。
     */
    @PostMapping("/unsent-ecode-sql")
    public Map<String, Object> unsentEcodeSqlPost(@RequestBody(required = false) Map<String, Object> body) {
        body = body == null ? Map.of() : body;
        String placepointId = numericOrBlank(body.get("placepointId"));
        String beginDate = dateOrBlank(body.get("beginDate"));
        String endDate = dateOrBlank(body.get("endDate"));
        String scene = strOrDefault(body.get("scene"), "all");
        boolean excludeAlreadySale = !Boolean.FALSE.equals(body.get("excludeAlreadySale"));
        boolean excludeAbnormal = Boolean.TRUE.equals(body.get("excludeAbnormal"));
        boolean excludePrecheckAbnormal = Boolean.TRUE.equals(body.get("excludePrecheckAbnormal"));
        return buildUnsentEcodeSql(placepointId, beginDate, endDate, scene,
            excludeAlreadySale, excludeAbnormal, excludePrecheckAbnormal);
    }

    @GetMapping("/unsent-ecode-sql")
    public Map<String, Object> unsentEcodeSqlGet(
            @RequestParam(required = false) String placepointId,
            @RequestParam(required = false) String beginDate,
            @RequestParam(required = false) String endDate,
            @RequestParam(defaultValue = "all") String scene,
            @RequestParam(defaultValue = "true") boolean excludeAlreadySale,
            @RequestParam(defaultValue = "false") boolean excludeAbnormal,
            @RequestParam(defaultValue = "false") boolean excludePrecheckAbnormal) {
        return buildUnsentEcodeSql(
            numericOrBlank(placepointId),
            dateOrBlank(beginDate),
            dateOrBlank(endDate),
            scene,
            excludeAlreadySale, excludeAbnormal, excludePrecheckAbnormal);
    }

    private static Map<String, Object> buildUnsentEcodeSql(String placepointId, String beginDate, String endDate,
                                                            String scene,
                                                            boolean excludeAlreadySale,
                                                            boolean excludeAbnormal,
                                                            boolean excludePrecheckAbnormal) {
        int days = beginDate.isEmpty() || endDate.isEmpty() ? 7 : 0;
        String normalizedScene = switch (scene == null ? "all" : scene.toLowerCase().trim()) {
            case "retail", "1" -> "retail";
            case "inout", "2" -> "inout";
            default -> "all";
        };

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("placepointId", placepointId);
        result.put("beginDate", beginDate);
        result.put("endDate", endDate);
        result.put("scene", normalizedScene);
        result.put("excludeAlreadySale", excludeAlreadySale);
        result.put("excludeAbnormal", excludeAbnormal);
        result.put("excludePrecheckAbnormal", excludePrecheckAbnormal);
        result.put("rule", "传过就算上传（不限 SYNC_FLAG），驳回也算上传；默认只排除已销售码（仅零售）");

        result.put("sql", unsentEcodeSql(placepointId, beginDate, endDate, days, normalizedScene,
            excludeAlreadySale, excludeAbnormal, excludePrecheckAbnormal));
        result.put("scenes", Map.of(
            "retail", "零售单上传 (uploadretail)，落 ALI_HEALTH_ECODE_SYNC_D，COMEFROM ∈ 270003/970002/730010/270013/255140",
            "inout", "出入库单据上传 (uploadinoutbill)，落 ALI_HEALTH_PURCH_ECODE_D，COMEFROM ∈ 260001/260003/260008/260034/260014/260016/260006/2561223",
            "all", "零售 + 出入库 UNION，任意一条没传都算未传"
        ));
        result.put("tables", Map.of(
            "采集记录", "GYGDPOS.BMS_ECODE_RECORD (零售+出入库共用)",
            "零售上传日志", "MSFX.ALI_HEALTH_ECODE_SYNC_D (不限 SYNC_FLAG，有记录=传过)",
            "出入库上传日志", "MSFX.ALI_HEALTH_PURCH_ECODE_D (不限 SYNC_FLAG，有记录=传过)",
            "已售出码", "MSFX.ALI_HEALTH_ALREADY_SALE_ECODE (仅零售，默认排除)",
            "平台驳回", "MSFX.ALI_HEALTH_ABNORMAL_ECODE (仅零售，默认不排除)",
            "预校验异常", "MSFX.ALI_SYNC_ECODE_ABNORMAL_DATA (仅零售，默认不排除)",
            "门店同步", "GYGDPOS.ALI_HEALTH_SYNC_STORE_D",
            "商品主数据", "GYGDPOS.PUB_GOODS"
        ));
        return result;
    }

    /** 零售 COMEFROM: 270003 零售退货 / 970002 前台开票 / 730010 中药开方 / 270013 订单销售 / 255140 慢病审方 */
    private static final String RETAIL_COMEFROM = "('270003','970002','730010','270013','255140')";
    /** 出入库 COMEFROM: 260001 配送收货 / 260003 直配收货 / 260008 配送调拨管理 / 260034 配送调拨确认 / 260014 报损 / 260016 报溢 / 260006 配送退货 / 2561223 配送退货含越库 */
    private static final String INOUT_COMEFROM = "('260001','260003','260008','260034','260014','260016','260006','2561223')";

    private static String unsentEcodeSql(String placepointId, String beginDate, String endDate, int days,
                                         String scene,
                                         boolean excludeAlreadySale,
                                         boolean excludeAbnormal,
                                         boolean excludePrecheckAbnormal) {
        String datePredicate;
        if (!beginDate.isEmpty() && !endDate.isEmpty()) {
            datePredicate = "  AND r.CREDATE >= TO_DATE('" + beginDate + "', 'YYYY-MM-DD')\n"
                + "  AND r.CREDATE <  TO_DATE('" + endDate + "', 'YYYY-MM-DD') + 1\n";
        } else {
            datePredicate = "  AND r.CREDATE >= TRUNC(SYSDATE) - " + days + "\n"
                + "  AND r.CREDATE <  SYSDATE\n";
        }

        boolean includeRetail = scene.equals("retail") || scene.equals("all");
        boolean includeInout = scene.equals("inout") || scene.equals("all");
        StringBuilder sb = new StringBuilder();
        boolean unionNeeded = false;

        if (includeRetail) {
            sb.append(retailUnsentSql(placepointId, datePredicate, excludeAlreadySale, excludeAbnormal, excludePrecheckAbnormal));
            unionNeeded = true;
        }
        if (includeInout) {
            if (unionNeeded) {
                sb.append("\nUNION ALL\n");
            }
            sb.append(inoutUnsentSql(placepointId, datePredicate));
        }
        sb.append("\nORDER BY \"门店ID\", \"采集时间\" DESC");
        return sb.toString();
    }

    private static String retailUnsentSql(String placepointId, String datePredicate,
                                          boolean excludeAlreadySale, boolean excludeAbnormal, boolean excludePrecheckAbnormal) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT\n");
        sb.append("  r.PLACEPOINTID        AS \"门店ID\",\n");
        sb.append("  s.PLACEPOINTNAME      AS \"门店名\",\n");
        sb.append("  r.ECODE               AS \"追溯码\",\n");
        sb.append("  r.GOODSID             AS \"商品ID\",\n");
        sb.append("  g.GOODSNAME           AS \"商品名\",\n");
        sb.append("  r.LOTNO               AS \"批号\",\n");
        sb.append("  r.GOODSQTY            AS \"数量\",\n");
        sb.append("  r.COMEFROM            AS \"业务来源\",\n");
        sb.append("  r.SOURCEID            AS \"单据明细ID\",\n");
        sb.append("  r.RSAID               AS \"销售单ID\",\n");
        sb.append("  'retail'              AS \"场景\",\n");
        sb.append("  TO_CHAR(r.CREDATE, 'YYYY-MM-DD HH24:MI:SS') AS \"采集时间\"\n");
        sb.append("FROM GYGDPOS.BMS_ECODE_RECORD r\n");
        sb.append("LEFT JOIN MSFX.ALI_HEALTH_ECODE_SYNC_D u\n");
        sb.append("  ON u.PLACEPOINTID = r.PLACEPOINTID\n");
        sb.append(" AND u.TRACE_CODE   = r.ECODE\n");
        sb.append("LEFT JOIN GYGDPOS.ALI_HEALTH_SYNC_STORE_D s\n");
        sb.append("  ON s.PLACEPOINTID = r.PLACEPOINTID AND s.USESTATUS = 1\n");
        sb.append("LEFT JOIN GYGDPOS.PUB_GOODS g\n");
        sb.append("  ON g.GOODSID = r.GOODSID\n");
        sb.append("WHERE u.TRACE_CODE IS NULL\n");
        sb.append("  AND r.COMEFROM IN ").append(RETAIL_COMEFROM).append('\n');
        if (excludeAlreadySale) {
            sb.append("  AND NOT EXISTS (SELECT 1 FROM MSFX.ALI_HEALTH_ALREADY_SALE_ECODE a\n");
            sb.append("                   WHERE a.TRACECODE = r.ECODE AND a.PLACEPOINTID = r.PLACEPOINTID)\n");
        }
        if (excludeAbnormal) {
            sb.append("  AND NOT EXISTS (SELECT 1 FROM MSFX.ALI_HEALTH_ABNORMAL_ECODE a\n");
            sb.append("                   WHERE a.TRACECODE = r.ECODE AND a.PLACEPOINTID = r.PLACEPOINTID)\n");
        }
        if (excludePrecheckAbnormal) {
            sb.append("  AND NOT EXISTS (SELECT 1 FROM MSFX.ALI_SYNC_ECODE_ABNORMAL_DATA a\n");
            sb.append("                   WHERE a.PLACEPOINTID = r.PLACEPOINTID\n");
            sb.append("                     AND a.RSADTLID = TO_CHAR(r.SOURCEID))\n");
        }
        sb.append(datePredicate);
        if (!placepointId.isEmpty()) {
            sb.append("  AND r.PLACEPOINTID = ").append(placepointId).append('\n');
        }
        return sb.toString();
    }

    private static String inoutUnsentSql(String placepointId, String datePredicate) {
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT\n");
        sb.append("  r.PLACEPOINTID        AS \"门店ID\",\n");
        sb.append("  s.PLACEPOINTNAME      AS \"门店名\",\n");
        sb.append("  r.ECODE               AS \"追溯码\",\n");
        sb.append("  r.GOODSID             AS \"商品ID\",\n");
        sb.append("  g.GOODSNAME           AS \"商品名\",\n");
        sb.append("  r.LOTNO               AS \"批号\",\n");
        sb.append("  r.GOODSQTY            AS \"数量\",\n");
        sb.append("  r.COMEFROM            AS \"业务来源\",\n");
        sb.append("  r.SOURCEID            AS \"出入库单据ID\",\n");
        sb.append("  NULL                  AS \"销售单ID\",\n");
        sb.append("  'inout'               AS \"场景\",\n");
        sb.append("  TO_CHAR(r.CREDATE, 'YYYY-MM-DD HH24:MI:SS') AS \"采集时间\"\n");
        sb.append("FROM GYGDPOS.BMS_ECODE_RECORD r\n");
        sb.append("LEFT JOIN MSFX.ALI_HEALTH_PURCH_ECODE_D p\n");
        sb.append("  ON p.PLACEPOINTID = r.PLACEPOINTID\n");
        sb.append(" AND p.COMEFROM     = r.COMEFROM\n");
        sb.append(" AND p.SOURCEID     = r.SOURCEID\n");
        sb.append(" AND p.GOODS_ID     = r.GOODSID\n");
        sb.append(" AND p.TRACE_CODE   = r.ECODE\n");
        sb.append("LEFT JOIN GYGDPOS.ALI_HEALTH_SYNC_STORE_D s\n");
        sb.append("  ON s.PLACEPOINTID = r.PLACEPOINTID AND s.USESTATUS = 1\n");
        sb.append("LEFT JOIN GYGDPOS.PUB_GOODS g\n");
        sb.append("  ON g.GOODSID = r.GOODSID\n");
        sb.append("WHERE p.TRACE_CODE IS NULL\n");
        sb.append("  AND r.COMEFROM IN ").append(INOUT_COMEFROM).append('\n');
        sb.append(datePredicate);
        if (!placepointId.isEmpty()) {
            sb.append("  AND r.PLACEPOINTID = ").append(placepointId).append('\n');
        }
        return sb.toString();
    }

    private static String strOrDefault(Object value, String defaultValue) {
        String s = value == null ? "" : value.toString().trim();
        return s.isEmpty() ? defaultValue : s;
    }

    private static String groupBuyMappingSql(List<String> screenshotDetailIds, List<String> realDetailIds) {
        return "SELECT groupbuydtlid AS screenshot_rsadtlid, rsadtlid AS real_rsadtlid\n"
            + "  FROM gygdpos.ZX_GROUP_BUY_DTL\n"
            + " WHERE groupbuydtlid IN (" + csvOrNull(screenshotDetailIds) + ")\n"
            + "    OR rsadtlid IN (" + csvOrNull(realDetailIds) + ")\n"
            + " ORDER BY groupbuydtlid, rsadtlid";
    }

    private static String realSaleDetailSql(List<String> realDetailIds, String rsaid) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT gsl.rsaid, gsl.rsadtlid, gso.placepointid,\n")
            .append("       TO_CHAR(gso.credate, 'yyyy-mm-dd hh24:mi:ss') AS gresa_credate,\n")
            .append("       TO_CHAR(gso.useday, 'yyyy-mm-dd') AS useday,\n")
            .append("       gsl.goodsid, g.goodsname, TO_NUMBER(gsl.goodsqty) AS goodscnt,\n")
            .append("       gsl.batchid, gsl.lotid, gso.usestatus AS doc_status, gsl.usestatus AS dtl_status\n")
            .append("  FROM gygdpos.gresa_sa_dtl gsl\n")
            .append("  JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid\n")
            .append("  JOIN gygdpos.pub_goods g ON gsl.goodsid = g.goodsid\n")
            .append(" WHERE gsl.rsadtlid IN (").append(csvOrNull(realDetailIds)).append(")");
        if (!rsaid.isEmpty()) {
            sql.append("\n    OR gsl.rsaid = ").append(rsaid);
        }
        sql.append("\n ORDER BY gsl.rsadtlid");
        return sql.toString();
    }

    private static String bmsEcodeByScreenshotDetailSql(List<String> screenshotDetailIds, List<String> traceCodes) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ecode, placepointid, comefrom, sourceid AS screenshot_rsadtlid, goodsid, lotid, inputmanid,\n")
            .append("       TO_CHAR(credate, 'yyyy-mm-dd hh24:mi:ss') AS bms_credate\n")
            .append("  FROM gygdpos.BMS_ECODE_RECORD\n")
            .append(" WHERE sourceid IN (").append(csvOrNull(screenshotDetailIds)).append(")");
        if (!traceCodes.isEmpty()) {
            sql.append("\n    OR ecode IN (").append(quotedCsv(traceCodes)).append(")");
        }
        sql.append("\n ORDER BY sourceid, ecode");
        return sql.toString();
    }

    private static String traceJoinCheckSql(List<String> realDetailIds) {
        return "SELECT gsl.rsadtlid AS real_rsadtlid, d.groupbuydtlid AS screenshot_rsadtlid,\n"
            + "       er.ecode, er.placepointid, er.goodsid, er.sourceid AS bms_sourceid,\n"
            + "       TO_CHAR(er.credate, 'yyyy-mm-dd hh24:mi:ss') AS bms_credate\n"
            + "  FROM gygdpos.gresa_sa_dtl gsl\n"
            + "  JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid\n"
            + "  LEFT JOIN gygdpos.ZX_GROUP_BUY_DTL d ON d.rsadtlid = gsl.rsadtlid\n"
            + "  JOIN gygdpos.BMS_ECODE_RECORD er\n"
            + "    ON er.placepointid = gso.placepointid\n"
            + "   AND er.goodsid = gsl.goodsid\n"
            + "   AND (er.sourceid = gsl.rsadtlid OR er.sourceid = d.groupbuydtlid)\n"
            + " WHERE gsl.rsadtlid IN (" + csvOrNull(realDetailIds) + ")\n"
            + " ORDER BY gsl.rsadtlid, er.ecode";
    }

    private static String dateWindowCheckSql(String placepointId, List<String> realDetailIds, String businessDate) {
        String date = businessDate.isEmpty() ? "<yyyy-mm-dd>" : businessDate;
        return "SELECT COUNT(*) AS cnt\n"
            + "  FROM gygdpos.gresa_sa_dtl gsl\n"
            + "  JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid\n"
            + " WHERE gso.placepointid = " + placepointId + "\n"
            + "   AND gsl.rsadtlid IN (" + csvOrNull(realDetailIds) + ")\n"
            + "   AND gso.credate >= TO_DATE('" + date + "', 'YYYY-MM-DD')\n"
            + "   AND gso.credate <  TO_DATE('" + date + "', 'YYYY-MM-DD') + 1";
    }

    private static String fullCandidateCheckSql(String parentAreaCode, String placepointId, List<String> realDetailIds, String businessDate) {
        String date = businessDate.isEmpty() ? "<yyyy-mm-dd>" : businessDate;
        return "SELECT gsl.rsadtlid, gsl.rsaid, gsl.goodsid, g.goodsname, TO_NUMBER(gsl.goodsqty) AS goodscnt,\n"
            + "       TO_CHAR(gso.credate, 'yyyy-mm-dd hh24:mi:ss') AS setl_time,\n"
            + "       syns.area_code, syns.parent_area_code, NVL(t1.strongcontrol, 0) AS strongcontrol,\n"
            + "       CASE WHEN t2.ecodetype IN (2,3,4,5,6) THEN 1 ELSE 0 END AS spececode,\n"
            + "       (SELECT COUNT(*) FROM gygdpos.BMS_ECODE_RECORD er\n"
            + "         WHERE er.placepointid = gso.placepointid AND er.goodsid = gsl.goodsid\n"
            + "           AND (er.sourceid = gsl.rsadtlid OR EXISTS (SELECT 1 FROM gygdpos.ZX_GROUP_BUY_DTL d WHERE d.rsadtlid = gsl.rsadtlid AND d.groupbuydtlid = er.sourceid))) AS trace_count\n"
            + "  FROM gygdpos.gresa_sa_dtl gsl\n"
            + "  JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid\n"
            + "  JOIN gygdpos.pub_goods g ON gsl.goodsid = g.goodsid AND g.usestatus = 1\n"
            + "  JOIN gygdpos.pub_goods_area ga ON gsl.goodsid = ga.goodsid AND ga.isecode = 1\n"
            + "  JOIN gygdpos.ali_health_sync_store_d syns ON syns.placepointid = gso.placepointid AND syns.usestatus = 1\n"
            + "  JOIN gygdpos.gpcs_placepoint gp ON gso.placepointid = gp.placepointid\n"
            + "  LEFT JOIN (\n"
            + "       SELECT a.area, a.goodsid, a.contype, b.strongcontrol, TO_NUMBER(" + parentAreaCode + ") AS placepointid\n"
            + "         FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a, gygdpos.ZX_AREA_GOODS_QUALITY b\n"
            + "        WHERE a.dtlid = b.dtlid AND NVL(a.usestatus, 0) = 1 AND a.qualityid = 9 AND a.area = " + parentAreaCode + "\n"
            + "       UNION\n"
            + "       SELECT a.area, a.goodsid, a.contype, c.strongcontrol, c.placepointid\n"
            + "         FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a, gygdpos.ZX_POINT_GOODS_QUALITY c\n"
            + "        WHERE a.dtlid = c.dtlid AND NVL(a.usestatus, 0) = 1 AND a.qualityid = 9 AND a.area = " + parentAreaCode + "\n"
            + "  ) t1 ON gsl.goodsid = t1.goodsid AND t1.placepointid IN (gso.placepointid, " + parentAreaCode + ")\n"
            + "  LEFT JOIN gygdpos.special_ecode_goods t2 ON gsl.goodsid = t2.goodsid\n"
            + " WHERE gso.usestatus = 1 AND gsl.usestatus = 1\n"
            + "   AND gso.placepointid = " + placepointId + "\n"
            + "   AND gsl.rsadtlid IN (" + csvOrNull(realDetailIds) + ")\n"
            + "   AND gso.credate >= TO_DATE('" + date + "', 'YYYY-MM-DD')\n"
            + "   AND gso.credate <  TO_DATE('" + date + "', 'YYYY-MM-DD') + 1\n"
            + "   AND ga.area = syns.parent_area_code\n"
            + "   AND NVL(t1.strongcontrol, 0) = 1\n"
            + "   AND CASE WHEN t2.ecodetype IN (2,3,4,5,6) THEN 1 ELSE 0 END = 0\n"
            + "   AND NOT EXISTS (SELECT 1 FROM MSFX.ALI_HEALTH_ECODE_SYNC_D l WHERE l.area_code = syns.area_code AND l.placepointid = gso.placepointid AND l.rsaid = gsl.rsaid AND l.rsadtlid = gsl.rsadtlid AND l.goods_id = gsl.goodsid)\n"
            + "   AND NOT EXISTS (SELECT 1 FROM MSFX.ALI_HEALTH_ALREADY_SALE_ECODE m WHERE m.placepointid = gso.placepointid AND m.rsadtlid = gsl.rsadtlid AND m.goods_id = gsl.goodsid)\n"
            + "   AND NOT EXISTS (SELECT 1 FROM MSFX.ALI_HEALTH_ABNORMAL_ECODE q WHERE q.placepointid = gso.placepointid AND q.rsadtlid = gsl.rsadtlid AND q.goods_id = gsl.goodsid)\n"
            + "   AND NOT EXISTS (SELECT 1 FROM MSFX.ALI_SYNC_ECODE_ABNORMAL_DATA n WHERE n.area_code = syns.area_code AND n.placepointid = gso.placepointid AND n.rsaid = gsl.rsaid AND n.rsadtlid = gsl.rsadtlid AND n.goods_id = gsl.goodsid)\n"
            + "   AND EXISTS (SELECT 1 FROM gygdpos.BMS_ECODE_RECORD er WHERE er.placepointid = gso.placepointid AND er.goodsid = gsl.goodsid AND (er.sourceid = gsl.rsadtlid OR EXISTS (SELECT 1 FROM gygdpos.ZX_GROUP_BUY_DTL d WHERE d.rsadtlid = gsl.rsadtlid AND d.groupbuydtlid = er.sourceid)))\n"
            + " ORDER BY gsl.rsadtlid";
    }

    private static String successLogCheckSql(String placepointId, List<String> realDetailIds, String rsaid, List<String> traceCodes) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT area_code, placepointid, placepointname, comefrom, rsaid, rsadtlid, goods_id, trace_code, setl_time, create_time, request_log_id\n")
            .append("  FROM MSFX.ALI_HEALTH_ECODE_SYNC_D\n")
            .append(" WHERE placepointid = ").append(placepointId)
            .append("\n   AND (rsadtlid IN (").append(csvOrNull(realDetailIds)).append(")");
        if (!rsaid.isEmpty()) {
            sql.append(" OR rsaid = ").append(rsaid);
        }
        if (!traceCodes.isEmpty()) {
            sql.append(" OR trace_code IN (").append(quotedCsv(traceCodes)).append(")");
        }
        sql.append(")\n ORDER BY rsadtlid, trace_code");
        return sql.toString();
    }

    private static String requestLogCheckSql(String rsaid, List<String> realDetailIds) {
        List<String> requestLogIds = new ArrayList<>();
        if (!rsaid.isEmpty()) {
            for (String id : realDetailIds) {
                requestLogIds.add("GDYFSA_" + rsaid + id);
            }
        }
        return "SELECT request_log_id, placepointid, refentid, request_id, response_success, msg_info\n"
            + "  FROM MSFX.ALI_HEALTH_SYNC_REQUSET_LOG\n"
            + " WHERE request_log_id IN (" + quotedCsv(requestLogIds) + ")\n"
            + " ORDER BY request_log_id";
    }

    private static String abnormalCheckSql(String placepointId, List<String> realDetailIds, String rsaid, List<String> traceCodes) {
        String detailCsv = csvOrNull(realDetailIds);
        String traceCsv = quotedCsv(traceCodes);
        String rsaidPredicate = rsaid.isEmpty() ? "1 = 0" : "rsaid = " + rsaid;
        return "SELECT area_code, placepointid, comefrom, rsaid, rsadtlid, goods_id, ext_msg_reason, 'PRECHECK' AS source_table\n"
            + "  FROM MSFX.ALI_SYNC_ECODE_ABNORMAL_DATA\n"
            + " WHERE placepointid = " + placepointId + " AND (rsadtlid IN (" + detailCsv + ") OR " + rsaidPredicate + ")\n"
            + "UNION ALL\n"
            + "SELECT NULL AS area_code, placepointid, NULL AS comefrom, NULL AS rsaid, rsadtlid, goods_id, abnormal_desc AS ext_msg_reason, 'ABNORMAL_ECODE' AS source_table\n"
            + "  FROM MSFX.ALI_HEALTH_ABNORMAL_ECODE\n"
            + " WHERE placepointid = " + placepointId + " AND (rsadtlid IN (" + detailCsv + ") OR tracecode IN (" + traceCsv + "))\n"
            + "UNION ALL\n"
            + "SELECT NULL AS area_code, placepointid, NULL AS comefrom, NULL AS rsaid, rsadtlid, goods_id, '已售出码' AS ext_msg_reason, 'ALREADY_SALE' AS source_table\n"
            + "  FROM MSFX.ALI_HEALTH_ALREADY_SALE_ECODE\n"
            + " WHERE placepointid = " + placepointId + " AND (rsadtlid IN (" + detailCsv + ") OR tracecode IN (" + traceCsv + "))";
    }

    private static String numericOrDefault(Object value, String defaultValue) {
        String s = value == null ? "" : value.toString().trim();
        return s.matches("\\d+") ? s : defaultValue;
    }

    private static String numericOrBlank(Object value) {
        String s = value == null ? "" : value.toString().trim();
        return s.matches("\\d+") ? s : "";
    }

    private static String dateOrBlank(Object value) {
        String s = value == null ? "" : value.toString().trim();
        return s.matches("\\d{4}-\\d{2}-\\d{2}") ? s : "";
    }

    private static List<String> numericList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof Iterable<?>) {
            for (Object item : (Iterable<?>) value) {
                out.addAll(numericList(item));
            }
        } else if (value != null) {
            for (String item : value.toString().split(",")) {
                String s = numericOrBlank(item);
                if (!s.isEmpty()) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static String csvOrNull(List<String> values) {
        return values.isEmpty() ? "NULL" : String.join(",", values);
    }

    private static String quotedCsv(List<String> values) {
        if (values.isEmpty()) {
            return "NULL";
        }
        List<String> quoted = new ArrayList<>();
        for (String value : values) {
            quoted.add("'" + value + "'");
        }
        return String.join(",", quoted);
    }
}
