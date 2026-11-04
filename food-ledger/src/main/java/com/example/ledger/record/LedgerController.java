package com.example.ledger.record;

import com.example.ledger.auth.CurrentStaff;
import com.example.ledger.auth.StaffView;
import com.example.ledger.common.ApiResponse;
import com.example.ledger.common.BizException;
import com.example.ledger.common.PageResult;
import com.example.ledger.export.LedgerExportService;
import com.example.ledger.record.dto.LedgerDetail;
import com.example.ledger.record.dto.LedgerQuery;
import com.example.ledger.record.dto.LedgerUpsertRequest;
import com.example.ledger.record.dto.LedgerView;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 台账接口。
 *
 * 注意：没有 DELETE。
 * 台账是给监管看的东西，删行等于抹掉痕迹——错了就作废并写原因。
 */
@RestController
@RequestMapping("/api/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private final LedgerService ledgerService;
    private final CurrentStaff currentStaff;
    private final LedgerExportService exportService;
    private final LedgerValueMapper valueMapper;
    private final com.example.ledger.template.TemplateService templateService;

    @GetMapping
    public ApiResponse<List<LedgerView>> list(LedgerQuery query) {
        return ApiResponse.ok(ledgerService.list(query));
    }

    /**
     * 导出台账 Excel。
     *
     * 复用列表的筛选条件：用户在界面上筛出什么，导出的就是什么，
     * 不做第二套查询——否则「看到 20 条、导出 200 条」这种事迟早发生。
     */
    @GetMapping("/export")
    public void export(LedgerQuery query, HttpServletResponse response) throws IOException {
        List<LedgerView> rows = ledgerService.list(query);
        String period = periodOf(query);
        String filename = "食材采购台账_" + LocalDate.now() + ".xlsx";

        response.setContentType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader("Content-Disposition",
                "attachment; filename*=UTF-8''"
                        + URLEncoder.encode(filename, StandardCharsets.UTF_8));
        try {
            exportService.write(response.getOutputStream(), rows, period);
        } catch (Exception e) {
            // 已经在写响应体了，不能再返回 JSON；只能记日志并中断
            throw new BizException(500, "导出失败：" + e.getMessage());
        }
    }

    private String periodOf(LedgerQuery query) {
        String from = query.getDateFrom() == null ? "不限" : query.getDateFrom().toString();
        String to = query.getDateTo() == null ? "不限" : query.getDateTo().toString();
        return from + " 至 " + to;
    }

    /**
     * 分页查询（界面用）。
     *
     * 与 /api/ledger 并存而不是替换它：
     * 导出必须拿到**全部**匹配行，一旦被页大小截断，
     * 交上去的台账就少了一半——这种错误没人会在意到。
     */
    @GetMapping("/page")
    public ApiResponse<PageResult<LedgerView>> page(LedgerQuery query) {
        return ApiResponse.ok(ledgerService.page(query));
    }

    @GetMapping("/{id}")
    public ApiResponse<LedgerDetail> get(@PathVariable Long id) {
        return ApiResponse.ok(ledgerService.get(id));
    }

    /** 按批号追溯：同一批次的食材都记了哪些 */
    @GetMapping("/trace")
    public ApiResponse<List<LedgerView>> trace(@RequestParam String batchNo) {
        return ApiResponse.ok(ledgerService.traceByBatch(batchNo));
    }

    /**
     * 手填录入（表单直接提交一张字段表）。
     *
     * 与 AI 录入走同一条路：字段表 → 同一套值映射 → 同一段判定。
     * 这两条路要是不共用代码，迟早出现「AI 填的能入账、手填的不能」。
     */
    @PostMapping("/values")
    public ApiResponse<LedgerDetail> createFromValues(@RequestBody ValuesBody body) {
        StaffView me = currentStaff.require();
        var schema = templateService.defaultSchema();
        var request = valueMapper.toRequest(schema, body.getValues() == null
                ? java.util.Map.of() : body.getValues());
        return ApiResponse.ok(ledgerService.create(request, me.getDisplayName()));
    }

    @lombok.Data
    public static class ValuesBody {
        private java.util.Map<String, Object> values;
        /** 修改时必填：乐观锁版本号 */
        private Integer version;
    }

    /**
     * 按「字段表」修改记录（与手填创建对称）。
     *
     * 前端提交的是模板字段的 key → 值，由同一个映射器转成 DTO。
     * 这样补录界面可以直接按模板渲染，不必在前端再维护一份
     * 「模板字段 ↔ 驼峰属性」的对照表——那份表一定会和模板跑偏，
     * 而且跑偏时表现为「某个字段在界面上根本不存在」。
     */
    @PutMapping("/{id}/values")
    public ApiResponse<LedgerDetail> updateFromValues(@PathVariable Long id,
                                                      @RequestBody ValuesBody body) {
        if (body.getVersion() == null) {
            throw new com.example.ledger.common.BizException("缺少 version，拒绝无锁更新");
        }
        StaffView me = currentStaff.require();
        var schema = templateService.defaultSchema();
        var request = valueMapper.toRequest(schema, body.getValues() == null
                ? java.util.Map.of() : body.getValues());
        request.setVersion(body.getVersion());
        return ApiResponse.ok(ledgerService.update(id, request, me.getDisplayName()));
    }

    @PostMapping
    public ApiResponse<LedgerDetail> create(@RequestBody LedgerUpsertRequest request) {
        return ApiResponse.ok(ledgerService.create(request, currentStaff.require().getDisplayName()));
    }

    /**
     * 修改。
     *
     * 规则：采购员只能改自己录的，管理员都能改。
     * 台账虽然是共享的，但「谁写的谁负责」这条不能松——
     * 不然出了错，没人说得清是谁改的。
     */
    @PutMapping("/{id}")
    public ApiResponse<LedgerDetail> update(@PathVariable Long id,
                                            @RequestBody LedgerUpsertRequest request) {
        StaffView me = currentStaff.require();
        LedgerDetail existing = ledgerService.get(id);
        if (!me.isManager() && !me.getDisplayName().equals(existing.getCreatedBy())) {
            throw new BizException(403, "只能修改自己录入的记录");
        }
        return ApiResponse.ok(ledgerService.update(id, request, me.getDisplayName()));
    }

    @PostMapping("/{id}/post")
    public ApiResponse<LedgerDetail> post(@PathVariable Long id,
                                          @RequestBody VersionBody body) {
        return ApiResponse.ok(ledgerService.post(id, body.getVersion(),
                currentStaff.requireManager().getDisplayName()));
    }

    @PostMapping("/{id}/return")
    public ApiResponse<LedgerDetail> returnBack(@PathVariable Long id,
                                                @RequestBody ReturnBody body) {
        return ApiResponse.ok(ledgerService.returnBack(id, body.getVersion(),
                body.getNote(), currentStaff.requireManager().getDisplayName()));
    }

    @PostMapping("/{id}/void")
    public ApiResponse<LedgerDetail> voidRecord(@PathVariable Long id,
                                                @RequestBody VoidBody body) {
        return ApiResponse.ok(ledgerService.voidRecord(id, body.getVersion(),
                body.getReason(), currentStaff.requireManager().getDisplayName()));
    }


    @Data
    public static class VersionBody {
        private Integer version;
    }

    @Data
    public static class ReturnBody {
        private Integer version;
        private String note;
    }

    @Data
    public static class VoidBody {
        private Integer version;
        private String reason;
    }
}