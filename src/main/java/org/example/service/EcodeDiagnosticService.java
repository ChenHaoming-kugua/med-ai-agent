package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.sql.Timestamp;
import java.time.format.DateTimeFormatter;

/**
 * 追溯码未上传原因诊断服务。
 * 基于 databridge 项目 getStoreSetlList 的 SQL 逻辑，逐项排查。
 */
@Service
public class EcodeDiagnosticService {

    private static final Logger log = LoggerFactory.getLogger(EcodeDiagnosticService.class);
    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final JdbcTemplate jdbc;

    public EcodeDiagnosticService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** 入库类型 comefrom（这些 sourceId 不是 rsadtlid，不能走零售诊断） */
    private static final Set<String> INBOUND_COMEFROM;
    static {
        Set<String> s = new HashSet<>();
        s.add("260001"); s.add("260003"); s.add("260006"); s.add("260008");
        s.add("260014"); s.add("260016"); s.add("260034"); s.add("2561223"); s.add("270003");
        INBOUND_COMEFROM = Collections.unmodifiableSet(s);
    }

    /** 通过追溯码查找并诊断 */
    public Map<String, Object> diagnoseByEcode(String ecode, long placepointid) {
        log.info("  [diagnoseByEcode] 开始 ecode={} placepointid={}", ecode, placepointid);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("ecode", ecode);
        r.put("placepointid", placepointid);

        // 从 bms_ecode_record 反查
        List<Map<String, Object>> records;
        try {
            records = jdbc.queryForList(
                "SELECT g.sourceid, g.goodsid, g.comefrom, g.placepointid, g.ecode " +
                "FROM gygdpos.bms_ecode_record g " +
                "WHERE g.ecode = ? AND g.placepointid = ? AND ROWNUM <= 10",
                ecode, placepointid);
        } catch (Exception ex) {
            log.error("  [diagnoseByEcode] bms_ecode_record 查询失败", ex);
            r.put("error", "查询 bms_ecode_record 失败: " + ex.getMessage());
            return r;
        }

        if (records.isEmpty()) {
            log.warn("  [diagnoseByEcode] ecode={} 未在bms_ecode_record中找到", ecode);
            r.put("error", "未在 bms_ecode_record 中找到追溯码 " + ecode + "（门店 " + placepointid + "），该码可能未被扫码采集");
            r.put("blocked", true);
            r.put("blockReason", "追溯码未采集: bms_ecode_record 中无此码记录");
            return r;
        }

        log.info("  [diagnoseByEcode] 找到 {} 条bms_ecode_record, 开始诊断", records.size());
        r.put("ecodeRecords", records);

        // 分类：零售出库 → 零售诊断，入库 → 入库诊断
        List<Map<String, Object>> inboundRecs = new ArrayList<>();
        List<Map<String, Object>> retailRecs = new ArrayList<>();
        for (Map<String, Object> rec : records) {
            String cf = str(rec.get("COMEFROM"));
            if (INBOUND_COMEFROM.contains(cf)) {
                inboundRecs.add(rec);
            } else {
                retailRecs.add(rec);
            }
        }
        log.info("  [diagnoseByEcode] 零售出库 {} 条, 入库/其他 {} 条", retailRecs.size(), inboundRecs.size());

        List<Map<String, Object>> diagnoses = new ArrayList<>();

        // 零售出库诊断
        for (Map<String, Object> rec : retailRecs) {
            long sourceId = toLong(rec.get("SOURCEID"));
            if (sourceId <= 0) continue;
            Map<String, Object> diag = diagnoseDetail(placepointid, sourceId);
            diagnoses.add(diag);
        }

        // 入库记录诊断：检查是否已在 ALI_HEALTH_PURCH_ECODE_D
        for (Map<String, Object> rec : inboundRecs) {
            diagnoses.add(diagnoseInbound(placepointid, rec));
        }

        if (diagnoses.isEmpty()) {
            r.put("error", "找到了追溯码记录但 sourceId 无效");
            return r;
        }

        Map<String, Object> primary = diagnoses.get(0);
        r.putAll(primary);
        if (diagnoses.size() > 1) {
            r.put("multipleDiagnoses", diagnoses);
            r.put("multipleDiagnosesNote", "该追溯码对应多条记录 (" + diagnoses.size() + " 条)，已逐一诊断");
        }

        return r;
    }

    /** 入库类型追溯码诊断 */
    private Map<String, Object> diagnoseInbound(long placepointid, Map<String, Object> rec) {
        long sourceId = toLong(rec.get("SOURCEID"));
        String comefrom = str(rec.get("COMEFROM"));
        String goodsid = str(rec.get("GOODSID"));
        String ecode = str(rec.get("ECODE"));
        log.info("    [diagnoseInbound] sourceId={} goodsid={} comefrom={}", sourceId, goodsid, comefrom);

        Map<String, Object> r = new LinkedHashMap<>();
        r.put("placepointid", placepointid);
        r.put("sourceId", sourceId);
        r.put("comefrom", comefrom);
        r.put("ecode", ecode);
        r.put("flowType", "入库/非零售");

        List<String> fails = new ArrayList<>();
        List<String> passes = new ArrayList<>();
        List<Map<String, Object>> checks = new ArrayList<>();

        long t;

        t = System.currentTimeMillis();
        Map<String, Object> chkStore = checkStoreSync(placepointid);
        log.info("    [checkStoreSync] cost={}ms", System.currentTimeMillis() - t);
        checks.add(chkStore);
        if (!isPass(chkStore)) fails.add("门店未配置码上放心同步");
        else passes.add("门店已配置码上放心同步");

        t = System.currentTimeMillis();
        Map<String, Object> chkPurch = checkPurchEcode(placepointid, sourceId, goodsid);
        log.info("    [checkPurchEcode] cost={}ms", System.currentTimeMillis() - t);
        checks.add(chkPurch);
        if (isPass(chkPurch)) fails.add("已存在于 ALI_HEALTH_PURCH_ECODE_D，入库记录已同步");
        else passes.add("未在 ALI_HEALTH_PURCH_ECODE_D 中");

        // 入库/退货记录：根据 comefrom 选不同前缀精确匹配
        // GDYFIO_{sourceId}_{goodsId}_{lotId}_{comeFrom} (入库, 缺lotId用前缀)
        // GDYFRT_{placepointid}_{returnId} (零售退货, returnId=sourceId)
        String logPrefix;
        if ("270003".equals(comefrom)) {
            logPrefix = "GDYFRT_" + placepointid + "_" + sourceId;
        } else {
            logPrefix = "GDYFIO_" + sourceId + "_" + goodsid + "_";
        }
        t = System.currentTimeMillis();
        Map<String, Object> chkLog = checkSyncLogByPrefix(logPrefix);
        log.info("    [checkSyncLog] cost={}ms", System.currentTimeMillis() - t);
        checks.add(chkLog);

        t = System.currentTimeMillis();
        Map<String, Object> chkEnt = checkStoreEnt(placepointid);
        log.info("    [checkStoreEnt] cost={}ms", System.currentTimeMillis() - t);
        checks.add(chkEnt);
        if (!isPass(chkEnt)) fails.add("门店缺少企业标识(ent_id/ref_ent_id)，无法上传入库记录");

        r.put("checks", checks);
        r.put("passes", passes);
        r.put("fails", fails);
        r.put("blocked", !fails.isEmpty());
        if (!fails.isEmpty()) r.put("blockReason", String.join("; ", fails));
        r.put("baseInfo", Map.of(
            "FLOW_TYPE", "入库/" + comefrom,
            "SOURCE_ID", sourceId,
            "ECODE", ecode
        ));

        return r;
    }

    /** 检查入库记录是否已同步到 ALI_HEALTH_PURCH_ECODE_D */
    private Map<String, Object> checkPurchEcode(long placepointid, long sourceId, String goodsid) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT 1 FROM MSFX.ALI_HEALTH_PURCH_ECODE_D " +
                "WHERE placepointid = ? AND sourceid = ? AND goods_id = ? AND ROWNUM <= 1",
                placepointid, sourceId, goodsid);
            boolean exists = !list.isEmpty();
            return Map.of("check", "ALI_HEALTH_PURCH_ECODE_D(入库已同步)",
                "pass", exists,
                "detail", exists ? "已存在，无需上传" : "不存在，未同步");
        } catch (Exception e) {
            return Map.of("check", "ALI_HEALTH_PURCH_ECODE_D(入库已同步)",
                "pass", false, "detail", "查询异常: " + e.getMessage());
        }
    }

    /** 检查门店是否有 ent_id */
    private Map<String, Object> checkStoreEnt(long placepointid) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT ent_id, ref_ent_id FROM gygdpos.ali_health_sync_store_d " +
                "WHERE placepointid = ? AND usestatus = 1 AND ROWNUM <= 1", placepointid);
            if (list.isEmpty()) {
                return Map.of("check", "门店企业标识(ent_id)",
                    "pass", false,
                    "detail", "门店不在同步列表或 usestatus != 1");
            }
            Map<String, Object> row = list.get(0);
            boolean hasEnt = !"".equals(str(row.get("ENT_ID")));
            return Map.of("check", "门店企业标识(ent_id)",
                "pass", hasEnt,
                "entId", str(row.get("ENT_ID")),
                "refEntId", str(row.get("REF_ENT_ID")),
                "detail", hasEnt ? "ent_id 已配置" : "ent_id 为空，无法上传");
        } catch (Exception e) {
            return Map.of("check", "门店企业标识(ent_id)",
                "pass", false, "detail", "查询异常: " + e.getMessage());
        }
    }

    /** 诊断单条零售明细为什么没被选入上传队列 */
    public Map<String, Object> diagnoseDetail(long placepointid, long rsadtlid) {
        log.info("  [diagnoseDetail] placepointid={} rsadtlid={}", placepointid, rsadtlid);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("placepointid", placepointid);
        r.put("rsadtlid", rsadtlid);

        // 0. 获取明细基本信息
        Map<String, Object> base = baseInfo(placepointid, rsadtlid);
        if (base == null || base.isEmpty()) {
            log.warn("  [diagnoseDetail] 未找到明细 placepointid={} rsadtlid={}", placepointid, rsadtlid);
            r.put("error", "未找到该明细记录，请检查 rsadtlid 和 placepointid 是否正确");
            r.put("blocked", true);
            r.put("blockReason", "明细不存在");
            return r;
        }
        r.put("baseInfo", base);

        long goodsid = toLong(base.get("GOODSID"));
        long rsaid = toLong(base.get("RSAID"));
        String rsaidStr = String.valueOf(rsaid);
        String goodsidStr = String.valueOf(goodsid);
        String rsadtlidStr = String.valueOf(rsadtlid);

        List<String> fails = new ArrayList<>();
        List<String> passes = new ArrayList<>();
        List<Map<String, Object>> details = new ArrayList<>();

        // 1. 商品是否启用追溯码
        Map<String, Object> chk1 = checkGoodsEcode(goodsid, placepointid);
        details.add(chk1);
        if (!isPass(chk1)) fails.add("商品未启用追溯码: isecode != 1 或 goods_area 无记录");
        else passes.add("商品已启用追溯码");

        // 2. 门店是否在码上放心同步列表
        Map<String, Object> chk2 = checkStoreSync(placepointid);
        details.add(chk2);
        if (!isPass(chk2)) fails.add("门店未配置码上放心同步: ali_health_sync_store_d 无记录或 usestatus!=1");
        else passes.add("门店已配置码上放心同步");

        // 3. 商品区域与门店同步区域是否匹配
        Map<String, Object> chk3 = checkAreaMatch(goodsid, placepointid);
        details.add(chk3);
        if (!isPass(chk3)) fails.add("商品区域与门店同步区域不匹配: ga.area != syns.parent_area_code");
        else passes.add("区域匹配");

        // 4. BMS_ECODE_RECORD 是否存在
        Map<String, Object> chk4 = checkEcodeRecord(placepointid, goodsid, rsadtlidStr);
        details.add(chk4);
        if (!isPass(chk4)) fails.add("追溯码采集记录不存在: bms_ecode_record 无匹配记录");
        else passes.add("追溯码采集记录存在");

        // 5. 是否已在 ALI_HEALTH_ECODE_SYNC_D (已成功同步)
        Map<String, Object> chk5 = checkAlreadySynced(placepointid, rsaidStr, rsadtlidStr, goodsidStr);
        details.add(chk5);
        if (isPass(chk5)) fails.add("已成功同步过: 记录存在于 ALI_HEALTH_ECODE_SYNC_D");
        else passes.add("未被同步过");

        // 6. 是否在 ALI_HEALTH_ALREADY_SALE_ECODE (已售出)
        Map<String, Object> chk6 = checkAlreadySale(placepointid, rsadtlidStr, goodsidStr);
        details.add(chk6);
        if (isPass(chk6)) fails.add("记录在 ALI_HEALTH_ALREADY_SALE_ECODE 中，追溯码已被标记为已售出");
        else passes.add("未被标记为已售出");

        // 7. 是否在 ALI_HEALTH_ABNORMAL_ECODE (异常)
        Map<String, Object> chk7 = checkAbnormal(placepointid, rsadtlidStr, goodsidStr);
        details.add(chk7);
        if (isPass(chk7)) fails.add("记录在 ALI_HEALTH_ABNORMAL_ECODE 中，追溯码异常(未激活/过期/码制校验失败等)");
        else passes.add("未被标记为异常");

        // 8. 是否在 ALI_SYNC_ECODE_ABNORMAL_DATA (数据不全)
        Map<String, Object> chk8 = checkAbnormalData(placepointid, rsaidStr, rsadtlidStr, goodsidStr);
        details.add(chk8);
        if (isPass(chk8)) fails.add("记录在 ALI_SYNC_ECODE_ABNORMAL_DATA 中，追溯码信息不全或为空");
        else passes.add("未被标记为数据异常");

        // 9. 强控/特殊追溯码检查
        Map<String, Object> chk9 = checkQuality(placepointid, goodsid);
        details.add(chk9);
        if (isPass(chk9)) passes.add("强控品/特殊追溯码检查通过"); // 强控=1 的会被排除，所以"存在强控"是 fail
        // 注意：原来的 SQL 中 strongcontrol=1 和 spececode=1 会排除记录
        // 但这里我们只报告，不判定 fail

        // 10. 同步日志检查
        Map<String, Object> chk10 = checkSyncLog(rsaidStr, rsadtlidStr, goodsidStr);
        details.add(chk10);
        if (isPass(chk10)) passes.add("有同步请求日志记录");
        else passes.add("无同步请求日志记录(可能从未尝试上传)");

        r.put("passes", passes);
        r.put("fails", fails);
        r.put("checks", details);
        r.put("blocked", !fails.isEmpty());
        if (!fails.isEmpty()) r.put("blockReason", String.join("; ", fails));
        log.info("  [diagnoseDetail] 结果: blocked={} fails={} passes={}",
                r.get("blocked"), fails.size(), passes.size());

        return r;
    }

    /** 批量诊断：按时间段+门店找出所有未上传的明细并归类 */
    public Map<String, Object> diagnoseBatch(long placepointid, String beginTime, String endTime) {
        log.info("  [diagnoseBatch] placepointid={} beginTime={} endTime={}", placepointid, beginTime, endTime);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("placepointid", placepointid);
        r.put("beginTime", beginTime);
        r.put("endTime", endTime);

        String sql =
            "WITH candidates AS (" +
            "SELECT DISTINCT gsl.rsadtlid, gsl.rsaid, gsl.goodsid, g.goodsname, " +
            "gso.credate, gso.placepointid, gp.placepointname, 1 strongcontrol, 0 spececode " +
            "FROM gygdpos.gresa_sa_dtl gsl " +
            "INNER JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid " +
            "INNER JOIN gygdpos.pub_goods g ON gsl.goodsid = g.goodsid AND g.usestatus = 1 " +
            "INNER JOIN gygdpos.pub_goods_area ga ON gsl.goodsid = ga.goodsid AND ga.isecode = 1 " +
            "INNER JOIN gygdpos.ali_health_sync_store_d syns ON syns.placepointid = gso.placepointid AND syns.usestatus = 1 " +
            "INNER JOIN gygdpos.gpcs_placepoint gp ON gso.placepointid = gp.placepointid " +
            "WHERE gso.usestatus = 1 AND gsl.usestatus = 1 " +
            "AND gso.placepointid = ? " +
            "AND gso.credate >= TO_DATE(?, 'YYYY-MM-DD') " +
            "AND gso.credate < TO_DATE(?, 'YYYY-MM-DD') + 1 " +
            "AND ga.area = syns.parent_area_code " +
            "AND (EXISTS( " +
            "SELECT 1 FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a " +
            "JOIN gygdpos.ZX_AREA_GOODS_QUALITY b ON a.dtlid = b.dtlid " +
            "WHERE a.goodsid = gsl.goodsid AND a.area = syns.parent_area_code " +
            "AND NVL(a.usestatus, 0) = 1 AND a.qualityid = 9 AND b.strongcontrol = 1) " +
            "OR EXISTS( " +
            "SELECT 1 FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a " +
            "JOIN gygdpos.ZX_POINT_GOODS_QUALITY c ON a.dtlid = c.dtlid " +
            "WHERE a.goodsid = gsl.goodsid AND a.area = syns.parent_area_code " +
            "AND NVL(a.usestatus, 0) = 1 AND a.qualityid = 9 " +
            "AND c.placepointid IN (gso.placepointid, syns.parent_area_code) AND c.strongcontrol = 1))" +
            "AND NOT EXISTS(SELECT 1 FROM gygdpos.special_ecode_goods t2 " +
            "WHERE t2.goodsid = gsl.goodsid AND t2.ecodetype IN (2,3,4,5,6)) " +
            "AND EXISTS( " +
            "SELECT 1 FROM gygdpos.BMS_ECODE_RECORD e0 " +
            "WHERE e0.placepointid = gso.placepointid " +
            "AND e0.goodsid = gsl.goodsid " +
            "AND (e0.sourceid = gsl.rsadtlid OR EXISTS(SELECT 1 FROM gygdpos.ZX_GROUP_BUY_DTL d0 " +
            "WHERE d0.rsadtlid = gsl.rsadtlid AND d0.groupbuydtlid = e0.sourceid))) " +
            "AND NOT EXISTS( " +
            "SELECT 1 FROM MSFX.ALI_HEALTH_ECODE_SYNC_D l " +
            "WHERE l.area_code = syns.area_code " +
            "AND l.placepointid = gso.placepointid " +
            "AND l.rsaid = gsl.rsaid " +
            "AND l.rsadtlid = gsl.rsadtlid " +
            "AND l.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS( " +
            "SELECT 1 FROM MSFX.ALI_HEALTH_ALREADY_SALE_ECODE m " +
            "WHERE m.placepointid = gso.placepointid " +
            "AND m.rsadtlid = gsl.rsadtlid " +
            "AND m.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS( " +
            "SELECT 1 FROM MSFX.ALI_HEALTH_ABNORMAL_ECODE q " +
            "WHERE q.placepointid = gso.placepointid " +
            "AND q.rsadtlid = gsl.rsadtlid " +
            "AND q.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS( " +
            "SELECT 1 FROM MSFX.ALI_SYNC_ECODE_ABNORMAL_DATA n " +
            "WHERE n.area_code = syns.area_code " +
            "AND n.placepointid = gso.placepointid " +
            "AND n.rsaid = gsl.rsaid " +
            "AND n.rsadtlid = gsl.rsadtlid " +
            "AND n.goods_id = gsl.goodsid) " +
            "AND ROWNUM <= 200), " +
            "ecode_match AS (" +
            "SELECT c.rsadtlid, COUNT(e.ecode) ecode_count, MIN(e.ecode) trace_code " +
            "FROM candidates c " +
            "LEFT JOIN gygdpos.bms_ecode_record e ON e.placepointid = c.placepointid " +
            "AND e.goodsid = c.goodsid " +
            "AND (e.sourceid = c.rsadtlid OR EXISTS(SELECT 1 FROM gygdpos.ZX_GROUP_BUY_DTL d " +
            "WHERE d.rsadtlid = c.rsadtlid AND d.groupbuydtlid = e.sourceid)) " +
            "GROUP BY c.rsadtlid) " +
            "SELECT c.rsadtlid, c.rsaid, c.goodsid, c.goodsname, c.credate, c.placepointid, c.placepointname, " +
            "c.strongcontrol, c.spececode, NVL(e.ecode_count, 0) ecode_count, e.trace_code " +
            "FROM candidates c " +
            "LEFT JOIN ecode_match e ON e.rsadtlid = c.rsadtlid " +
            "ORDER BY c.credate DESC";

        List<Map<String, Object>> unsent;
        try {
            unsent = jdbc.queryForList(sql, placepointid, beginTime, endTime);
        } catch (Exception e) {
            log.error("  [diagnoseBatch] 查询失败: {}", e.getMessage());
            r.put("error", "查询失败: " + e.getMessage());
            return r;
        }

        log.info("  [diagnoseBatch] 找到 {} 条未上传明细", unsent.size());
        r.put("unsentCount", unsent.size());
        if (unsent.isEmpty()) {
            r.put("summary", "该时间段内所有零售明细均已上传或已处理");
            return r;
        }

        // 批量查 REQUSET_LOG，区分"未触发"和"触发但失败"
        Map<String, Map<String, Object>> syncLogMap = Collections.emptyMap();
        List<String> logIds = new ArrayList<>();
        for (Map<String, Object> row : unsent) {
            long ecodeCount = toLong(row.get("ECODE_COUNT"));
            if (ecodeCount > 0) {
                logIds.add("GDYFSA_" + str(row.get("RSAID")) + str(row.get("RSADTLID")));
            }
        }
        if (!logIds.isEmpty()) {
            try {
                StringBuilder inClause = new StringBuilder();
                for (int i = 0; i < logIds.size(); i++) {
                    if (i > 0) inClause.append(",");
                    inClause.append("?");
                }
                List<Map<String, Object>> logRows = jdbc.queryForList(
                    "SELECT request_log_id, response_success, created_time " +
                    "FROM MSFX.ALI_HEALTH_SYNC_REQUSET_LOG " +
                    "WHERE request_log_id IN (" + inClause + ")",
                    logIds.toArray());
                syncLogMap = new LinkedHashMap<>();
                for (Map<String, Object> lr : logRows) {
                    syncLogMap.put(str(lr.get("REQUEST_LOG_ID")), lr);
                }
                log.info("  [diagnoseBatch] REQUSET_LOG 批量查询: 查 {} 个, 命中 {}", logIds.size(), syncLogMap.size());
            } catch (Exception e) {
                log.warn("  [diagnoseBatch] REQUSET_LOG 批量查询失败: {}", e.getMessage());
            }
        }

        List<Map<String, Object>> reasons = new ArrayList<>();
        int noEcodeRecord = 0;
        int hasEcodeButNotUploaded = 0;
        int alreadySyncedFalsePositive = 0;

        for (Map<String, Object> row : unsent) {
            long ecodeCount = toLong(row.get("ECODE_COUNT"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rsadtlid", toLong(row.get("RSADTLID")));
            item.put("rsaid", str(row.get("RSAID")));
            item.put("goodsid", toLong(row.get("GOODSID")));
            item.put("goodsname", str(row.get("GOODSNAME")));
            item.put("traceCode", str(row.get("TRACE_CODE")));
            item.put("strongControl", toLong(row.get("STRONGCONTROL")));
            item.put("specialEcode", toLong(row.get("SPECECODE")));
            item.put("ecodeCount", ecodeCount);
            item.put("credate", row.get("CREDATE"));
            if (ecodeCount > 0) {
                String logId = "GDYFSA_" + str(row.get("RSAID")) + str(row.get("RSADTLID"));
                Map<String, Object> logEntry = syncLogMap.get(logId);
                item.put("requestLogId", logId);
                if (logEntry != null) {
                    String successFlag = str(logEntry.get("RESPONSE_SUCCESS"));
                    if ("1".equals(successFlag)) {
                        item.put("reason", "已上传成功(REQUSET_LOG有成功记录，UI延迟/SYNC_D写入延迟，非真实未上传)");
                        alreadySyncedFalsePositive++;
                        item.put("syncTriggered", true);
                        item.put("syncSuccess", true);
                    } else {
                        item.put("reason", "已触发上传但接口返回失败: response_success=0，需排查接口层(msg_info)");
                        hasEcodeButNotUploaded++;
                        item.put("syncTriggered", true);
                        item.put("syncSuccess", false);
                    }
                } else {
                    item.put("reason", "调度层未触发: 无同步请求日志，同步调度程序未扫描/未触发该单据，需排查scheduler或强控品同步通道");
                    hasEcodeButNotUploaded++;
                    item.put("syncTriggered", false);
                    item.put("syncSuccess", false);
                }
            } else {
                item.put("reason", "无追溯码采集记录(BMS_ECODE_RECORD)，零售时未扫码或扫码数据未入库");
                noEcodeRecord++;
            }
            reasons.add(item);
        }

        r.put("noEcodeRecord", noEcodeRecord);
        r.put("hasEcodeButNotUploaded", hasEcodeButNotUploaded);
        r.put("alreadySyncedFalsePositive", alreadySyncedFalsePositive);
        r.put("details", reasons);
        r.put("summary", String.format(
            "共 %d 条: %d 条已上传(假阳性/UI延迟), %d 条真实未上传(%d 条未触发+%d 条已触发但失败需看REQUSET_LOG), %d 条无采集记录(需检查扫码环节)",
            unsent.size(), alreadySyncedFalsePositive,
            hasEcodeButNotUploaded,
            reasons.stream().filter(it -> Boolean.FALSE.equals(it.get("syncTriggered"))).count(),
            reasons.stream().filter(it -> Boolean.TRUE.equals(it.get("syncTriggered")) && Boolean.FALSE.equals(it.get("syncSuccess"))).count(),
            noEcodeRecord));

        return r;
    }

    /** 按时间段+门店找出已在 ALI_HEALTH_ECODE_SYNC_D 的上传记录数（用于对比） */
    public Map<String, Object> diagnoseUploadStats(long placepointid, String beginTime, String endTime) {
        log.info("  [diagnoseUploadStats] placepointid={} beginTime={} endTime={}", placepointid, beginTime, endTime);
        Map<String, Object> r = new LinkedHashMap<>();

        // 该门店时间段内零售单总数
        long totalDetail = q(
            "SELECT COUNT(*) FROM gygdpos.gresa_sa_dtl gsl " +
            "INNER JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid " +
            "WHERE gso.usestatus = 1 AND gsl.usestatus = 1 " +
            "AND gso.placepointid = ? " +
            "AND gso.credate >= TO_DATE(?, 'YYYY-MM-DD') " +
            "AND gso.credate < TO_DATE(?, 'YYYY-MM-DD') + 1",
            placepointid, beginTime, endTime);

        // 原上传链路候选明细数：启用追溯码、强控=1、特殊追溯码=0、已采集追溯码
        long ecodeGoodsDetail = q(
            "SELECT COUNT(DISTINCT gsl.rsadtlid) FROM gygdpos.gresa_sa_dtl gsl " +
            "INNER JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid " +
            "INNER JOIN gygdpos.pub_goods_area ga ON gsl.goodsid = ga.goodsid AND ga.isecode = 1 " +
            "INNER JOIN gygdpos.ali_health_sync_store_d syns ON syns.placepointid = gso.placepointid AND syns.usestatus = 1 " +
            "WHERE gso.usestatus = 1 AND gsl.usestatus = 1 " +
            "AND gso.placepointid = ? " +
            "AND gso.credate >= TO_DATE(?, 'YYYY-MM-DD') " +
            "AND gso.credate < TO_DATE(?, 'YYYY-MM-DD') + 1 " +
            "AND ga.area = syns.parent_area_code " +
            "AND (EXISTS(SELECT 1 FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a " +
            "JOIN gygdpos.ZX_AREA_GOODS_QUALITY b ON a.dtlid = b.dtlid " +
            "WHERE a.goodsid = gsl.goodsid AND a.area = syns.parent_area_code " +
            "AND NVL(a.usestatus, 0) = 1 AND a.qualityid = 9 AND b.strongcontrol = 1) " +
            "OR EXISTS(SELECT 1 FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a " +
            "JOIN gygdpos.ZX_POINT_GOODS_QUALITY c ON a.dtlid = c.dtlid " +
            "WHERE a.goodsid = gsl.goodsid AND a.area = syns.parent_area_code " +
            "AND NVL(a.usestatus, 0) = 1 AND a.qualityid = 9 " +
            "AND c.placepointid IN (gso.placepointid, syns.parent_area_code) AND c.strongcontrol = 1))" +
            "AND NOT EXISTS(SELECT 1 FROM gygdpos.special_ecode_goods t2 " +
            "WHERE t2.goodsid = gsl.goodsid AND t2.ecodetype IN (2,3,4,5,6)) " +
            "AND EXISTS(SELECT 1 FROM gygdpos.BMS_ECODE_RECORD e0 " +
            "WHERE e0.placepointid = gso.placepointid AND e0.goodsid = gsl.goodsid " +
            "AND (e0.sourceid = gsl.rsadtlid OR EXISTS(SELECT 1 FROM gygdpos.ZX_GROUP_BUY_DTL d0 " +
            "WHERE d0.rsadtlid = gsl.rsadtlid AND d0.groupbuydtlid = e0.sourceid)))",
            placepointid, beginTime, endTime);

        long syncedCount;

        // 异常记录数 (ALI_HEALTH_ABNORMAL_ECODE 无时间字段，按门店查全量)
        long abnormalCount = q(
            "SELECT COUNT(*) FROM MSFX.ALI_HEALTH_ABNORMAL_ECODE " +
            "WHERE placepointid = ?",
            placepointid);

        // 已售出记录数 (无时间字段，按门店查全量)
        long alreadySaleCount = q(
            "SELECT COUNT(*) FROM MSFX.ALI_HEALTH_ALREADY_SALE_ECODE " +
            "WHERE placepointid = ?",
            placepointid);

        // 数据不全记录数 (无时间字段，按门店查全量)
        long abnormalDataCount = q(
            "SELECT COUNT(*) FROM MSFX.ALI_SYNC_ECODE_ABNORMAL_DATA " +
            "WHERE placepointid = ?",
            placepointid);

        long unsentDetailCount = q(
            "SELECT COUNT(DISTINCT gsl.rsadtlid) " +
            "FROM gygdpos.gresa_sa_dtl gsl " +
            "INNER JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid " +
            "INNER JOIN gygdpos.pub_goods_area ga ON gsl.goodsid = ga.goodsid AND ga.isecode = 1 " +
            "INNER JOIN gygdpos.ali_health_sync_store_d syns ON syns.placepointid = gso.placepointid AND syns.usestatus = 1 " +
            "WHERE gso.usestatus = 1 AND gsl.usestatus = 1 " +
            "AND gso.placepointid = ? " +
            "AND gso.credate >= TO_DATE(?, 'YYYY-MM-DD') " +
            "AND gso.credate < TO_DATE(?, 'YYYY-MM-DD') + 1 " +
            "AND ga.area = syns.parent_area_code " +
            "AND (EXISTS(SELECT 1 FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a " +
            "JOIN gygdpos.ZX_AREA_GOODS_QUALITY b ON a.dtlid = b.dtlid " +
            "WHERE a.goodsid = gsl.goodsid AND a.area = syns.parent_area_code " +
            "AND NVL(a.usestatus, 0) = 1 AND a.qualityid = 9 AND b.strongcontrol = 1) " +
            "OR EXISTS(SELECT 1 FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a " +
            "JOIN gygdpos.ZX_POINT_GOODS_QUALITY c ON a.dtlid = c.dtlid " +
            "WHERE a.goodsid = gsl.goodsid AND a.area = syns.parent_area_code " +
            "AND NVL(a.usestatus, 0) = 1 AND a.qualityid = 9 " +
            "AND c.placepointid IN (gso.placepointid, syns.parent_area_code) AND c.strongcontrol = 1))" +
            "AND NOT EXISTS(SELECT 1 FROM gygdpos.special_ecode_goods t2 " +
            "WHERE t2.goodsid = gsl.goodsid AND t2.ecodetype IN (2,3,4,5,6)) " +
            "AND EXISTS(SELECT 1 FROM gygdpos.BMS_ECODE_RECORD e0 " +
            "WHERE e0.placepointid = gso.placepointid AND e0.goodsid = gsl.goodsid " +
            "AND (e0.sourceid = gsl.rsadtlid OR EXISTS(SELECT 1 FROM gygdpos.ZX_GROUP_BUY_DTL d0 " +
            "WHERE d0.rsadtlid = gsl.rsadtlid AND d0.groupbuydtlid = e0.sourceid))) " +
            "AND NOT EXISTS(SELECT 1 FROM MSFX.ALI_HEALTH_ECODE_SYNC_D l " +
            "WHERE l.area_code = syns.area_code AND l.placepointid = gso.placepointid " +
            "AND l.rsaid = gsl.rsaid AND l.rsadtlid = gsl.rsadtlid AND l.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS(SELECT 1 FROM MSFX.ALI_HEALTH_ALREADY_SALE_ECODE m " +
            "WHERE m.placepointid = gso.placepointid AND m.rsadtlid = gsl.rsadtlid AND m.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS(SELECT 1 FROM MSFX.ALI_HEALTH_ABNORMAL_ECODE q " +
            "WHERE q.placepointid = gso.placepointid AND q.rsadtlid = gsl.rsadtlid AND q.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS(SELECT 1 FROM MSFX.ALI_SYNC_ECODE_ABNORMAL_DATA n " +
            "WHERE n.area_code = syns.area_code AND n.placepointid = gso.placepointid " +
            "AND n.rsaid = gsl.rsaid AND n.rsadtlid = gsl.rsadtlid AND n.goods_id = gsl.goodsid)",
            placepointid, beginTime, endTime);

        long missedCount = q(
            "SELECT COUNT(DISTINCT gsl.rsadtlid) " +
            "FROM gygdpos.gresa_sa_dtl gsl " +
            "INNER JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid " +
            "INNER JOIN gygdpos.pub_goods_area ga ON gsl.goodsid = ga.goodsid AND ga.isecode = 1 " +
            "INNER JOIN gygdpos.ali_health_sync_store_d syns ON syns.placepointid = gso.placepointid AND syns.usestatus = 1 " +
            "WHERE gso.usestatus = 1 AND gsl.usestatus = 1 " +
            "AND gso.placepointid = ? " +
            "AND gso.credate >= TO_DATE(?, 'YYYY-MM-DD') " +
            "AND gso.credate < TO_DATE(?, 'YYYY-MM-DD') + 1 " +
            "AND ga.area = syns.parent_area_code " +
            "AND (EXISTS(SELECT 1 FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a " +
            "JOIN gygdpos.ZX_AREA_GOODS_QUALITY b ON a.dtlid = b.dtlid " +
            "WHERE a.goodsid = gsl.goodsid AND a.area = syns.parent_area_code " +
            "AND NVL(a.usestatus, 0) = 1 AND a.qualityid = 9 AND b.strongcontrol = 1) " +
            "OR EXISTS(SELECT 1 FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a " +
            "JOIN gygdpos.ZX_POINT_GOODS_QUALITY c ON a.dtlid = c.dtlid " +
            "WHERE a.goodsid = gsl.goodsid AND a.area = syns.parent_area_code " +
            "AND NVL(a.usestatus, 0) = 1 AND a.qualityid = 9 " +
            "AND c.placepointid IN (gso.placepointid, syns.parent_area_code) AND c.strongcontrol = 1))" +
            "AND NOT EXISTS(SELECT 1 FROM gygdpos.special_ecode_goods t2 " +
            "WHERE t2.goodsid = gsl.goodsid AND t2.ecodetype IN (2,3,4,5,6)) " +
            "AND EXISTS(SELECT 1 FROM gygdpos.BMS_ECODE_RECORD g " +
            "WHERE g.placepointid = gso.placepointid AND g.goodsid = gsl.goodsid " +
            "AND (g.sourceid = gsl.rsadtlid OR EXISTS(SELECT 1 FROM gygdpos.ZX_GROUP_BUY_DTL d " +
            "WHERE d.rsadtlid = gsl.rsadtlid AND d.groupbuydtlid = g.sourceid))) " +
            "AND NOT EXISTS(SELECT 1 FROM MSFX.ALI_HEALTH_ECODE_SYNC_D l " +
            "WHERE l.placepointid = gso.placepointid AND l.rsaid = gsl.rsaid " +
            "AND l.rsadtlid = gsl.rsadtlid AND l.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS(SELECT 1 FROM MSFX.ALI_HEALTH_ALREADY_SALE_ECODE m " +
            "WHERE m.placepointid = gso.placepointid AND m.rsadtlid = gsl.rsadtlid AND m.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS(SELECT 1 FROM MSFX.ALI_HEALTH_ABNORMAL_ECODE q " +
            "WHERE q.placepointid = gso.placepointid AND q.rsadtlid = gsl.rsadtlid AND q.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS(SELECT 1 FROM MSFX.ALI_SYNC_ECODE_ABNORMAL_DATA n " +
            "WHERE n.placepointid = gso.placepointid AND n.rsaid = gsl.rsaid " +
            "AND n.rsadtlid = gsl.rsadtlid AND n.goods_id = gsl.goodsid)",
            placepointid, beginTime, endTime);

        syncedCount = Math.max(0, ecodeGoodsDetail - unsentDetailCount);

        r.put("placepointid", placepointid);
        r.put("timeRange", beginTime + " ~ " + endTime);
        r.put("totalDetailCount", totalDetail);
        r.put("ecodeGoodsDetailCount", ecodeGoodsDetail);
        r.put("syncedCount", syncedCount);
        r.put("abnormalCount", abnormalCount);
        r.put("alreadySaleCount", alreadySaleCount);
        r.put("abnormalDataCount", abnormalDataCount);
        r.put("unsentDetailCount", unsentDetailCount);
        r.put("missedCount", missedCount);
        r.put("uploadRate", ecodeGoodsDetail > 0
            ? String.format("%.1f%%", 100.0 * syncedCount / ecodeGoodsDetail)
            : "N/A");
        log.info("  [diagnoseUploadStats] total={} ecodeGoods={} synced={} missed={} uploadRate={}",
                totalDetail, ecodeGoodsDetail, syncedCount, missedCount, r.get("uploadRate"));

        return r;
    }

    /* ==================== 各项检查 ==================== */

    private Map<String, Object> baseInfo(long placepointid, long rsadtlid) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT gsl.rsadtlid, gsl.rsaid, gsl.goodsid, g.goodsname, gso.placepointid, " +
                "gp.placepointname, gso.credate, gso.rsatype " +
                "FROM gygdpos.gresa_sa_dtl gsl " +
                "INNER JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid " +
                "INNER JOIN gygdpos.pub_goods g ON gsl.goodsid = g.goodsid " +
                "INNER JOIN gygdpos.gpcs_placepoint gp ON gso.placepointid = gp.placepointid " +
                "WHERE gsl.rsadtlid = ? AND gso.placepointid = ?",
                rsadtlid, placepointid);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            log.warn("baseInfo: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> checkGoodsEcode(long goodsid, long placepointid) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT ga.goodsid, ga.area, ga.isecode " +
                "FROM gygdpos.pub_goods_area ga " +
                "INNER JOIN gygdpos.ali_health_sync_store_d syns ON syns.parent_area_code = ga.area " +
                "WHERE ga.goodsid = ? AND syns.placepointid = ? AND syns.usestatus = 1",
                goodsid, placepointid);
            if (list.isEmpty()) {
                return Map.of("check", "商品追溯码启用状态",
                    "pass", false,
                    "detail", "pub_goods_area 中 goodsid=" + goodsid + " 无 isecode=1 记录或与门店同步区域不匹配");
            }
            Map<String, Object> row = list.get(0);
            boolean isecode = "1".equals(str(row.get("ISECODE")));
            return Map.of("check", "商品追溯码启用状态",
                "pass", isecode,
                "isecode", isecode,
                "area", str(row.get("AREA")));
        } catch (Exception e) {
            return Map.of("check", "商品追溯码启用状态", "pass", false, "error", e.getMessage());
        }
    }

    private Map<String, Object> checkStoreSync(long placepointid) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT placepointid, area_code, parent_area_code, ent_id, ref_ent_id, network_type, usestatus " +
                "FROM gygdpos.ali_health_sync_store_d WHERE placepointid = ?", placepointid);
            if (list.isEmpty()) {
                return Map.of("check", "门店码上放心同步配置",
                    "pass", false,
                    "detail", "ali_health_sync_store_d 中无此门店记录");
            }
            Map<String, Object> row = list.get(0);
            boolean usestatus = "1".equals(str(row.get("USESTATUS")));
            return Map.of("check", "门店码上放心同步配置",
                "pass", usestatus,
                "entId", str(row.get("ENT_ID")),
                "refEntId", str(row.get("REF_ENT_ID")),
                "detail", usestatus ? "已配置且启用" : "已配置但 usestatus != 1");
        } catch (Exception e) {
            return Map.of("check", "门店码上放心同步配置", "pass", false, "error", e.getMessage());
        }
    }

    private Map<String, Object> checkAreaMatch(long goodsid, long placepointid) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT ga.area AS goods_area, syns.parent_area_code AS sync_area " +
                "FROM gygdpos.pub_goods_area ga, gygdpos.ali_health_sync_store_d syns " +
                "WHERE ga.goodsid = ? AND syns.placepointid = ? AND ga.isecode = 1",
                goodsid, placepointid);
            if (list.isEmpty()) {
                return Map.of("check", "商品区域与门店同步区域匹配",
                    "pass", false,
                    "detail", "无交集记录");
            }
            // 检查是否有任意一条 ga.area = syns.parent_area_code
            boolean match = list.stream().anyMatch(r ->
                Objects.equals(str(r.get("GOODS_AREA")), str(r.get("SYNC_AREA"))));
            return Map.of("check", "商品区域与门店同步区域匹配",
                "pass", match,
                "combinations", list.size(),
                "detail", match ? "匹配" : "不匹配: ga.area != syns.parent_area_code");
        } catch (Exception e) {
            return Map.of("check", "商品区域与门店同步区域匹配", "pass", false, "error", e.getMessage());
        }
    }

    private Map<String, Object> checkEcodeRecord(long placepointid, long goodsid, String sourceId) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT g.sourceid, g.ecode, g.goodsid, g.comefrom, g.placepointid " +
                "FROM gygdpos.bms_ecode_record g " +
                "WHERE g.placepointid = ? AND g.goodsid = ? " +
                "AND (g.sourceid = ? OR EXISTS(SELECT 1 FROM gygdpos.ZX_GROUP_BUY_DTL d " +
                "WHERE d.rsadtlid = ? AND d.groupbuydtlid = g.sourceid)) " +
                "AND ROWNUM <= 10",
                placepointid, goodsid, sourceId, sourceId);
            boolean exists = !list.isEmpty();
            return Map.of("check", "追溯码采集记录(BMS_ECODE_RECORD)",
                "pass", exists,
                "count", list.size(),
                "detail", exists ? "存在 " + list.size() + " 条匹配记录" : "不存在匹配记录，零售时未扫码或sourceid不匹配");
        } catch (Exception e) {
            return Map.of("check", "追溯码采集记录(BMS_ECODE_RECORD)", "pass", false, "error", e.getMessage());
        }
    }

    private Map<String, Object> checkAlreadySynced(long placepointid, String rsaid, String rsadtlid, String goodsid) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT 1 FROM MSFX.ALI_HEALTH_ECODE_SYNC_D " +
                "WHERE placepointid = ? AND rsaid = ? AND rsadtlid = ? AND goods_id = ? AND ROWNUM <= 1",
                placepointid, rsaid, rsadtlid, goodsid);
            boolean exists = !list.isEmpty();
            return Map.of("check", "ALI_HEALTH_ECODE_SYNC_D(已成功同步)",
                "pass", exists,
                "detail", exists ? "已存在，无需上传" : "不存在，可以上传");
        } catch (Exception e) {
            // 表可能不存在（分月表），返回 false
            return Map.of("check", "ALI_HEALTH_ECODE_SYNC_D(已成功同步)",
                "pass", false, "detail", "查询异常(表可能不存在): " + e.getMessage());
        }
    }

    private Map<String, Object> checkAlreadySale(long placepointid, String rsadtlid, String goodsid) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT 1 FROM MSFX.ALI_HEALTH_ALREADY_SALE_ECODE " +
                "WHERE placepointid = ? AND rsadtlid = ? AND goods_id = ? AND ROWNUM <= 1",
                placepointid, rsadtlid, goodsid);
            boolean exists = !list.isEmpty();
            return Map.of("check", "ALI_HEALTH_ALREADY_SALE_ECODE(已售出)",
                "pass", exists,
                "detail", exists ? "已存在，追溯码已售出，不再上传" : "不存在");
        } catch (Exception e) {
            return Map.of("check", "ALI_HEALTH_ALREADY_SALE_ECODE(已售出)",
                "pass", false, "detail", "查询异常: " + e.getMessage());
        }
    }

    private Map<String, Object> checkAbnormal(long placepointid, String rsadtlid, String goodsid) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT abnormal_type, abnormal_desc FROM MSFX.ALI_HEALTH_ABNORMAL_ECODE " +
                "WHERE placepointid = ? AND rsadtlid = ? AND goods_id = ? AND ROWNUM <= 1",
                placepointid, rsadtlid, goodsid);
            boolean exists = !list.isEmpty();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("check", "ALI_HEALTH_ABNORMAL_ECODE(异常追溯码)");
            r.put("pass", exists);
            if (exists) {
                r.put("abnormalType", list.get(0).get("ABNORMAL_TYPE"));
                r.put("abnormalDesc", str(list.get(0).get("ABNORMAL_DESC")));
                r.put("detail", "异常类型: " + list.get(0).get("ABNORMAL_TYPE"));
            } else {
                r.put("detail", "不存在");
            }
            return r;
        } catch (Exception e) {
            return Map.of("check", "ALI_HEALTH_ABNORMAL_ECODE(异常追溯码)",
                "pass", false, "detail", "查询异常: " + e.getMessage());
        }
    }

    private Map<String, Object> checkAbnormalData(long placepointid, String rsaid, String rsadtlid, String goodsid) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT ext_msg_reason FROM MSFX.ALI_SYNC_ECODE_ABNORMAL_DATA " +
                "WHERE placepointid = ? AND rsaid = ? AND rsadtlid = ? AND goods_id = ? AND ROWNUM <= 1",
                placepointid, rsaid, rsadtlid, goodsid);
            boolean exists = !list.isEmpty();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("check", "ALI_SYNC_ECODE_ABNORMAL_DATA(数据不全)");
            r.put("pass", exists);
            if (exists) {
                r.put("reason", str(list.get(0).get("EXT_MSG_REASON")));
                r.put("detail", "数据不全原因: " + str(list.get(0).get("EXT_MSG_REASON")));
            } else {
                r.put("detail", "不存在");
            }
            return r;
        } catch (Exception e) {
            return Map.of("check", "ALI_SYNC_ECODE_ABNORMAL_DATA(数据不全)",
                "pass", false, "detail", "查询异常: " + e.getMessage());
        }
    }

    private Map<String, Object> checkQuality(long placepointid, long goodsid) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT nvl(t1.strongcontrol, 0) as strongcontrol, " +
                "case when t2.ecodetype in (2,3,4,5,6) then 1 else 0 end as spececode " +
                "FROM DUAL " +
                "LEFT JOIN (SELECT a.area, a.goodsid, b.strongcontrol, ? as placepointid " +
                "FROM gygdpos.ZX_PUB_GOODS_AREA_DTL a, gygdpos.ZX_AREA_GOODS_QUALITY b " +
                "WHERE a.dtlid = b.dtlid AND nvl(a.usestatus, 0) = 1 AND a.qualityid = 9 " +
                "AND a.goodsid = ? AND ROWNUM <= 1) t1 ON 1=1 " +
                "LEFT JOIN gygdpos.special_ecode_goods t2 ON t2.goodsid = ? " +
                "WHERE ROWNUM <= 1",
                placepointid, goodsid, goodsid);
            if (list.isEmpty()) {
                return Map.of("check", "强控品/特殊追溯码", "pass", false, "strongcontrol", 0, "spececode", 0);
            }
            Map<String, Object> row = list.get(0);
            long sc = toLong(row.get("STRONGCONTROL"));
            long sp = toLong(row.get("SPECECODE"));
            return Map.of("check", "强控品/特殊追溯码",
                "pass", true,
                "strongcontrol", sc,
                "spececode", sp,
                "detail", (sc == 1 ? "是强控品(strongcontrol=1) " : "非强控品 ") +
                          (sp == 1 ? "是特殊追溯码(spececode=1)" : "非特殊追溯码"));
        } catch (Exception e) {
            return Map.of("check", "强控品/特殊追溯码", "pass", true, "error", e.getMessage());
        }
    }

    private Map<String, Object> checkSyncLog(String rsaid, String rsadtlid, String goodsid) {
        try {
            // 零售出库: request_log_id = GDYFSA_{rsaid}{rsadtlid}
            String exactId = "GDYFSA_" + rsaid + rsadtlid;
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT request_log_id, created_time, response_success " +
                "FROM MSFX.ALI_HEALTH_SYNC_REQUSET_LOG " +
                "WHERE request_log_id = ? AND ROWNUM <= 1",
                exactId);
            boolean exists = !list.isEmpty();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("check", "ALI_HEALTH_SYNC_REQUSET_LOG(同步请求日志)");
            r.put("pass", exists);
            r.put("requestLogId", exactId);
            if (exists) {
                r.put("createdTime", list.get(0).get("CREATED_TIME"));
                r.put("responseSuccess", list.get(0).get("RESPONSE_SUCCESS"));
                r.put("detail", "有同步日志 (request_log_id=" + exactId + ")");
            } else {
                r.put("detail", "无同步日志 (request_log_id=" + exactId + ")，可能从未尝试上传");
            }
            return r;
        } catch (Exception e) {
            return Map.of("check", "ALI_HEALTH_SYNC_REQUSET_LOG(同步请求日志)",
                "pass", false, "detail", "查询异常: " + e.getMessage());
        }
    }

    private Map<String, Object> checkSyncLogByPrefix(String prefix) {
        try {
            List<Map<String, Object>> list = jdbc.queryForList(
                "SELECT request_log_id, created_time, response_success " +
                "FROM MSFX.ALI_HEALTH_SYNC_REQUSET_LOG " +
                "WHERE request_log_id LIKE ? AND ROWNUM <= 3",
                prefix + "%");
            boolean exists = !list.isEmpty();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("check", "ALI_HEALTH_SYNC_REQUSET_LOG(同步请求日志)");
            r.put("pass", exists);
            r.put("count", list.size());
            if (exists) {
                r.put("detail", "有 " + list.size() + " 条同步日志 (prefix=" + prefix + ")");
            } else {
                r.put("detail", "无同步日志 (prefix=" + prefix + ")，可能从未尝试上传");
            }
            return r;
        } catch (Exception e) {
            return Map.of("check", "ALI_HEALTH_SYNC_REQUSET_LOG(同步请求日志)",
                "pass", false, "detail", "查询异常: " + e.getMessage());
        }
    }

    /* ==================== 上传请求日志诊断(单据号重复 / 重试排查) ==================== */

    /** 按 request_log_id 诊断(单据号重复、平台返回错误) */
    public Map<String, Object> diagnoseByRequestLogId(String requestLogId) {
        log.info("  [diagnoseByRequestLogId] requestLogId={}", requestLogId);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("requestLogId", requestLogId);

        if (requestLogId == null || requestLogId.isBlank()) {
            r.put("error", "requestLogId 不能为空");
            return r;
        }

        return runRequestLogDiagnosis("request_log_id", requestLogId, null);
    }

    /** 按平台单据号 (REQUEST_ID) 诊断 */
    public Map<String, Object> diagnoseByDocNo(String requestId, Long placepointid) {
        log.info("  [diagnoseByDocNo] requestId={} placepointid={}", requestId, placepointid);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("requestId", requestId);
        if (placepointid != null) r.put("placepointid", placepointid);

        if (requestId == null || requestId.isBlank()) {
            r.put("error", "requestId 不能为空");
            return r;
        }

        // 单据号是平台分配,可能跨门店共用号段,可选地按门店过滤
        if (placepointid != null && placepointid > 0) {
            return runRequestLogDiagnosis("request_id", requestId, placepointid);
        }
        return runRequestLogDiagnosis("request_id", requestId, null);
    }

    /** 按 placepointid + rsaid + rsadtlid 找最近一次同步日志(GDYFSA_<rsaid><rsadtlid>) */
    public Map<String, Object> diagnoseByRsaid(long placepointid, long rsaid, long rsadtlid) {
        log.info("  [diagnoseByRsaid] placepointid={} rsaid={} rsadtlid={}", placepointid, rsaid, rsadtlid);
        String requestLogId = "GDYFSA_" + rsaid + rsadtlid;
        Map<String, Object> r = diagnoseByRequestLogId(requestLogId);
        r.put("placepointid", placepointid);
        r.put("rsaid", rsaid);
        r.put("rsadtlid", rsadtlid);
        return r;
    }

    /** 核心:按 (column, value, 可选 placepointid) 查 ALI_HEALTH_SYNC_REQUSET_LOG 全部重试,归因 */
    private Map<String, Object> runRequestLogDiagnosis(String column, String value, Long placepointid) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("lookupColumn", column);
        r.put("lookupValue", value);

        // 防 SQL 注入:白名单列名
        if (!"request_log_id".equals(column) && !"request_id".equals(column)) {
            r.put("error", "非法的查询列: " + column);
            return r;
        }

        StringBuilder sql = new StringBuilder(
            "SELECT request_log_id, request_id, placepointid, refentid, " +
            "response_success, created_time, " +
            "SUBSTR(msg_info, 1, 500) AS msg_info, " +
            "SUBSTR(response_param, 1, 500) AS response_param, " +
            "SUBSTR(reqest_param, 1, 500) AS reqest_param " +
            "FROM MSFX.ALI_HEALTH_SYNC_REQUSET_LOG " +
            "WHERE " + column + " = ? ");
        List<Object> params = new ArrayList<>();
        params.add(value);
        if (placepointid != null && placepointid > 0) {
            sql.append("AND placepointid = ? ");
            params.add(placepointid);
        }
        sql.append("ORDER BY created_time ASC");

        List<Map<String, Object>> rows;
        try {
            // 大表(3000w+),用 ROWNUM 限制最多 50 条防 timeout
            String finalSql = "SELECT * FROM (" + sql + ") WHERE ROWNUM <= 50";
            rows = jdbc.queryForList(finalSql, params.toArray());
        } catch (Exception e) {
            log.error("  [runRequestLogDiagnosis] 查询失败", e);
            r.put("error", "查询 ALI_HEALTH_SYNC_REQUSET_LOG 失败: " + e.getMessage());
            return r;
        }

        r.put("attemptCount", rows.size());
        r.put("attempts", rows);

        if (rows.isEmpty()) {
            r.put("verdict", "NO_LOG");
            r.put("verdictReason", "在 ALI_HEALTH_SYNC_REQUSET_LOG 中没找到任何记录(写入前失败,或 request_log_id 与系统实际生成的不一致)");
            r.put("suggestion", "检查上游生成 request_log_id 的代码,确认日志写入逻辑(异常分支是否吞掉了 INSERT)");
            r.put("blocked", true);
            r.put("blockReason", "无同步请求日志");
            return r;
        }

        // 归类 attempts
        int successCount = 0;
        int failCount = 0;
        String firstReqId = null;
        String firstPlatformAssigned = null;
        Timestamp firstTime = null;
        Timestamp lastTime = null;
        Set<String> requestIds = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String success = str(row.get("RESPONSE_SUCCESS"));
            String reqId = str(row.get("REQUEST_ID"));
            String msg = str(row.get("MSG_INFO"));
            Timestamp t = toTs(row.get("CREATED_TIME"));
            if (!reqId.isEmpty()) requestIds.add(reqId);
            if ("1".equals(success)) successCount++;
            else if ("0".equals(success)) failCount++;
            if (firstTime == null) firstTime = t;
            lastTime = t;
            // 从 msg_info 抽取 "请更改单据号 XXXXX"
            String assigned = extractAssignedDocNo(msg);
            if (assigned != null && firstPlatformAssigned == null) firstPlatformAssigned = assigned;
        }
        if (!requestIds.isEmpty()) firstReqId = requestIds.iterator().next();

        r.put("successCount", successCount);
        r.put("failCount", failCount);
        r.put("distinctRequestIds", new ArrayList<>(requestIds));
        r.put("firstAttemptTime", firstTime);
        r.put("lastAttemptTime", lastTime);
        if (firstPlatformAssigned != null) {
            r.put("platformAssignedNewId", firstPlatformAssigned);
        }

        // 判定根因
        String verdict;
        String reason;
        String suggestion;
        if (successCount > 0) {
            // 已经成功过,又来一遍 → 真重复
            verdict = "REAL_DUPLICATE";
            reason = "该单据号已有 " + successCount + " 次 response_success='1' 的成功记录,但当前批次又重传了一次,平台因此拒绝";
            suggestion = "短期:从号段里换一个未用过的单据号(平台建议 " +
                (firstPlatformAssigned != null ? firstPlatformAssigned : "见 platformAssignedNewId 字段") +
                ")重试;长期:排查为什么已成功的单据又进了上传队列(去重逻辑有 bug)";
        } else if (failCount > 1) {
            verdict = "RETRY_LOOP";
            reason = "response_success='0' 出现 " + failCount + " 次,说明在反复重传同一单据号但每次都失败";
            long intervalMs = (lastTime != null && firstTime != null) ? lastTime.getTime() - firstTime.getTime() : -1;
            if (intervalMs >= 0 && intervalMs < 5 * 60 * 1000L) {
                reason += "(间隔 " + (intervalMs / 1000) + " 秒,大概率网络/超时重试)";
                suggestion = "检查重试逻辑:重试前是否重新申请新号,是否带幂等键(requestId)";
            } else if (intervalMs >= 0) {
                reason += "(间隔 " + (intervalMs / 1000 / 60) + " 分钟,大概率手工重传或定时任务重复)";
                suggestion = "检查定时任务/手工重传:重传前要换新单据号,且要做幂等去重";
            } else {
                suggestion = "检查重试逻辑和单据号管理";
            }
        } else if (failCount == 1) {
            verdict = "FIRST_FAIL";
            reason = "只有 1 条失败记录,没有重试历史——这是首次上传就失败";
            // 检查 msg_info 看平台给的具体原因
            String msg = str(rows.get(0).get("MSG_INFO"));
            if (msg.contains("单据号") && msg.contains("重复")) {
                verdict = "PLATFORM_REJECTED_DOC_NO";
                reason = "平台以'单据号重复'为由拒绝,但本地日志里没有该单据号的历史成功记录——可能:1) 平台去重逻辑有 bug;2) 该单据号曾被其他门店/企业占用;3) 号段管理异常,POS 端生成的号跟平台记录的号冲突";
                suggestion = "短期:按平台建议的新号 " + (firstPlatformAssigned != null ? firstPlatformAssigned : "(见 msg)") + " 重试;长期:把号段管理改成'先申请再用',不要本地缓存复用";
            } else {
                suggestion = "查看 msg_info 里的具体平台错误,按平台返回的原因修复";
            }
        } else {
            verdict = "UNKNOWN";
            reason = "无法归类";
            suggestion = "查看 attempts 中的 msg_info";
        }

        r.put("verdict", verdict);
        r.put("verdictReason", reason);
        r.put("suggestion", suggestion);
        r.put("blocked", true);
        r.put("blockReason", "单据号上传重复(verdict=" + verdict + ")");
        return r;
    }

    /** 从 msg_info 文本中抽 "请更改单据号 XXXXX" 的新号 */
    private String extractAssignedDocNo(String msg) {
        if (msg == null || msg.isEmpty()) return null;
        int idx = msg.indexOf("请更改单据号");
        if (idx < 0) {
            // 平台有时换说法
            idx = msg.indexOf("更改单据号");
            if (idx < 0) return null;
        }
        String tail = msg.substring(idx);
        // 截取数字串
        StringBuilder num = new StringBuilder();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (Character.isDigit(c)) num.append(c);
            else if (num.length() > 0) break;
        }
        return num.length() > 0 ? num.toString() : null;
    }

    private Timestamp toTs(Object v) {
        if (v == null) return null;
        if (v instanceof Timestamp) return (Timestamp) v;
        if (v instanceof java.sql.Date) return new Timestamp(((java.sql.Date) v).getTime());
        try { return Timestamp.valueOf(v.toString()); } catch (Exception e) { return null; }
    }

    /* ==================== 工具 ==================== */

    private boolean isPass(Map<String, Object> check) {
        return Boolean.TRUE.equals(check.get("pass"));
    }

    private long toLong(Object v) {
        if (v instanceof Number) return ((Number) v).longValue();
        if (v == null) return 0;
        try { return Long.parseLong(v.toString()); } catch (Exception e) { return 0; }
    }

    private String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private long q(String sql, Object... params) {
        try {
            Long val = jdbc.queryForObject(sql, Long.class, params);
            return val != null ? val : 0L;
        } catch (Exception e) {
            log.warn("SQL失败: {}", e.getMessage());
            return 0L;
        }
    }
}
