package org.example.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
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
                "SELECT 1 FROM ALI_HEALTH_PURCH_ECODE_D " +
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

        // 查找该门店在时间段内所有应上传但未上传的明细
        String sql =
            "SELECT gsl.rsadtlid, gsl.rsaid, gsl.goodsid, g.goodsname, " +
            "gso.credate, gso.placepointid, gp.placepointname " +
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
            "AND NOT EXISTS( " +
            "SELECT 1 FROM ALI_HEALTH_ECODE_SYNC_D l " +
            "WHERE l.area_code = syns.area_code " +
            "AND l.placepointid = gso.placepointid " +
            "AND l.rsaid = gsl.rsaid " +
            "AND l.rsadtlid = gsl.rsadtlid " +
            "AND l.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS( " +
            "SELECT 1 FROM ALI_HEALTH_ALREADY_SALE_ECODE m " +
            "WHERE m.placepointid = gso.placepointid " +
            "AND m.rsadtlid = gsl.rsadtlid " +
            "AND m.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS( " +
            "SELECT 1 FROM ALI_HEALTH_ABNORMAL_ECODE q " +
            "WHERE q.placepointid = gso.placepointid " +
            "AND q.rsadtlid = gsl.rsadtlid " +
            "AND q.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS( " +
            "SELECT 1 FROM ALI_SYNC_ECODE_ABNORMAL_DATA n " +
            "WHERE n.area_code = syns.area_code " +
            "AND n.placepointid = gso.placepointid " +
            "AND n.rsaid = gsl.rsaid " +
            "AND n.rsadtlid = gsl.rsadtlid " +
            "AND n.goods_id = gsl.goodsid) " +
            "AND ROWNUM <= 200";

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

        // 对每条未上传记录做 BMS_ECODE_RECORD 检查（这是最常见的失败原因）
        List<Map<String, Object>> reasons = new ArrayList<>();
        int noEcodeRecord = 0;
        int hasEcodeButNotUploaded = 0;

        for (Map<String, Object> row : unsent) {
            long ppid = toLong(row.get("PLACEPOINTID"));
            long rsadtlid = toLong(row.get("RSADTLID"));
            long goodsid = toLong(row.get("GOODSID"));
            String goodsname = str(row.get("GOODSNAME"));
            String rsaidStr = str(row.get("RSAID"));

            // 只查 BMS_ECODE_RECORD（最关键的检查）
            Map<String, Object> ecodeCheck = checkEcodeRecord(ppid, goodsid, String.valueOf(rsadtlid));

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rsadtlid", rsadtlid);
            item.put("rsaid", rsaidStr);
            item.put("goodsid", goodsid);
            item.put("goodsname", goodsname);
            item.put("credate", row.get("CREDATE"));
            if (isPass(ecodeCheck)) {
                item.put("reason", "追溯码采集记录存在但未上传，需进一步排查(可能是强控品/特殊追溯码/上游系统未触发同步)");
                hasEcodeButNotUploaded++;
            } else {
                item.put("reason", "无追溯码采集记录(BMS_ECODE_RECORD)，零售时未扫码或扫码数据未入库");
                noEcodeRecord++;
            }
            item.put("ecodeRecord", ecodeCheck);
            reasons.add(item);
        }

        r.put("noEcodeRecord", noEcodeRecord);
        r.put("hasEcodeButNotUploaded", hasEcodeButNotUploaded);
        r.put("details", reasons);
        r.put("summary", String.format(
            "共 %d 条未上传: %d 条无追溯码采集记录(需检查扫码环节), %d 条有采集记录但未上传(需进一步排查)",
            unsent.size(), noEcodeRecord, hasEcodeButNotUploaded));

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

        // 其中已启用ecode的商品明细数
        long ecodeGoodsDetail = q(
            "SELECT COUNT(*) FROM gygdpos.gresa_sa_dtl gsl " +
            "INNER JOIN gygdpos.gresa_sa_doc gso ON gsl.rsaid = gso.rsaid " +
            "INNER JOIN gygdpos.pub_goods_area ga ON gsl.goodsid = ga.goodsid AND ga.isecode = 1 " +
            "INNER JOIN gygdpos.ali_health_sync_store_d syns ON syns.placepointid = gso.placepointid AND syns.usestatus = 1 " +
            "WHERE gso.usestatus = 1 AND gsl.usestatus = 1 " +
            "AND gso.placepointid = ? " +
            "AND gso.credate >= TO_DATE(?, 'YYYY-MM-DD') " +
            "AND gso.credate < TO_DATE(?, 'YYYY-MM-DD') + 1 " +
            "AND ga.area = syns.parent_area_code",
            placepointid, beginTime, endTime);

        // 已成功上传的明细数
        long syncedCount = q(
            "SELECT COUNT(*) FROM ALI_HEALTH_ECODE_SYNC_D e " +
            "WHERE e.placepointid = ? " +
            "AND e.create_time >= TO_DATE(?, 'YYYY-MM-DD') " +
            "AND e.create_time < TO_DATE(?, 'YYYY-MM-DD') + 1",
            placepointid, beginTime, endTime);

        // 异常记录数 (ALI_HEALTH_ABNORMAL_ECODE 无时间字段，按门店查全量)
        long abnormalCount = q(
            "SELECT COUNT(*) FROM ALI_HEALTH_ABNORMAL_ECODE " +
            "WHERE placepointid = ?",
            placepointid);

        // 已售出记录数 (无时间字段，按门店查全量)
        long alreadySaleCount = q(
            "SELECT COUNT(*) FROM ALI_HEALTH_ALREADY_SALE_ECODE " +
            "WHERE placepointid = ?",
            placepointid);

        // 数据不全记录数 (无时间字段，按门店查全量)
        long abnormalDataCount = q(
            "SELECT COUNT(*) FROM ALI_SYNC_ECODE_ABNORMAL_DATA " +
            "WHERE placepointid = ?",
            placepointid);

        // BMS_ECODE_RECORD 有记录但未在任何目标表里的（漏网之鱼）
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
            "AND EXISTS( " +
            "SELECT 1 FROM gygdpos.BMS_ECODE_RECORD g " +
            "WHERE g.placepointid = gso.placepointid " +
            "AND g.goodsid = gsl.goodsid " +
            "AND (g.sourceid = gsl.rsadtlid " +
            "OR EXISTS(SELECT 1 FROM gygdpos.ZX_GROUP_BUY_DTL d " +
            "WHERE d.rsadtlid = gsl.rsadtlid AND d.groupbuydtlid = g.sourceid))) " +
            "AND NOT EXISTS(SELECT 1 FROM ALI_HEALTH_ECODE_SYNC_D l " +
            "WHERE l.placepointid = gso.placepointid AND l.rsaid = gsl.rsaid " +
            "AND l.rsadtlid = gsl.rsadtlid AND l.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS(SELECT 1 FROM ALI_HEALTH_ALREADY_SALE_ECODE m " +
            "WHERE m.placepointid = gso.placepointid AND m.rsadtlid = gsl.rsadtlid AND m.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS(SELECT 1 FROM ALI_HEALTH_ABNORMAL_ECODE q " +
            "WHERE q.placepointid = gso.placepointid AND q.rsadtlid = gsl.rsadtlid AND q.goods_id = gsl.goodsid) " +
            "AND NOT EXISTS(SELECT 1 FROM ALI_SYNC_ECODE_ABNORMAL_DATA n " +
            "WHERE n.placepointid = gso.placepointid AND n.rsaid = gsl.rsaid " +
            "AND n.rsadtlid = gsl.rsadtlid AND n.goods_id = gsl.goodsid)",
            placepointid, beginTime, endTime);

        r.put("placepointid", placepointid);
        r.put("timeRange", beginTime + " ~ " + endTime);
        r.put("totalDetailCount", totalDetail);
        r.put("ecodeGoodsDetailCount", ecodeGoodsDetail);
        r.put("syncedCount", syncedCount);
        r.put("abnormalCount", abnormalCount);
        r.put("alreadySaleCount", alreadySaleCount);
        r.put("abnormalDataCount", abnormalDataCount);
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
                "SELECT 1 FROM ALI_HEALTH_ECODE_SYNC_D " +
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
                "SELECT 1 FROM ALI_HEALTH_ALREADY_SALE_ECODE " +
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
                "SELECT abnormal_type, abnormal_desc FROM ALI_HEALTH_ABNORMAL_ECODE " +
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
                "SELECT ext_msg_reason FROM ALI_SYNC_ECODE_ABNORMAL_DATA " +
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
                "FROM ALI_HEALTH_SYNC_REQUSET_LOG " +
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
                "FROM ALI_HEALTH_SYNC_REQUSET_LOG " +
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
