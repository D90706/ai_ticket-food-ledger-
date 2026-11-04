package com.example.ledger.export;

import com.example.ledger.record.dto.LedgerView;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 台账导出成 .xlsx。
 *
 * 为什么不用 CSV：
 *   CSV 打开常乱码，得靠 BOM 和调用方碰运气；
 *   而且检查时要的是有表头、有边框、能直接打印的东西。
 *   台账最终要落在纸上，这一步不该省。
 *
 * 版式是照着"能直接交上去"设计的：
 *   第一行标题（含导出条件）
 *   第二行说明（导出时间、条数、系统名）
 *   第三行表头，冻结
 *   之后是数据，金额与数量用数字格式，日期用文本避免被 Excel 猜错
 */
@Slf4j
@Service
public class LedgerExportService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 表头与取值一一对应，顺序就是纸质台账的阅读顺序：先法定项目，再金额，最后审计 */
    private static final String[] HEADERS = {
            "序号", "进货日期", "名称", "规格", "数量", "单位",
            "生产日期", "生产批号", "保质期", "到期日",
            "供货者名称", "供货者地址", "联系方式", "索证索票凭证",
            "单价(元)", "金额(元)", "入库仓库", "验收人",
            "合规级别", "状态", "录入人", "录入方式", "内容修订"
    };

    public void write(OutputStream out, List<LedgerView> rows, String period) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("采购台账");

            CellStyle titleStyle = titleStyle(workbook);
            CellStyle infoStyle = infoStyle(workbook);
            CellStyle headerStyle = headerStyle(workbook);
            CellStyle bodyStyle = bodyStyle(workbook);
            CellStyle numberStyle = numberStyle(workbook);

            int lastCol = HEADERS.length - 1;

            // ---- 标题 ----
            Row titleRow = sheet.createRow(0);
            titleRow.setHeightInPoints(28);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue("食材采购台账");
            titleCell.setCellStyle(titleStyle);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, lastCol));

            // ---- 说明行：把"这批数据是什么范围"写清楚，否则打印出来没人知道 ----
            Row infoRow = sheet.createRow(1);
            Cell infoCell = infoRow.createCell(0);
            infoCell.setCellValue("进货日期：" + period
                    + "　　共 " + rows.size() + " 条"
                    + "　　导出时间：" + LocalDateTime.now().format(TIME));
            infoCell.setCellStyle(infoStyle);
            sheet.addMergedRegion(new CellRangeAddress(1, 1, 0, lastCol));

            // ---- 表头 ----
            Row headerRow = sheet.createRow(2);
            headerRow.setHeightInPoints(22);
            for (int i = 0; i < HEADERS.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }
            sheet.createFreezePane(0, 3);

            // ---- 数据 ----
            int rowIndex = 3;
            int seq = 1;
            for (LedgerView v : rows) {
                Row row = sheet.createRow(rowIndex++);
                int col = 0;
                setText(row, col++, String.valueOf(seq++), bodyStyle);
                setText(row, col++, format(v.getPurchaseDate()), bodyStyle);
                setText(row, col++, nvl(v.getIngredientName()), bodyStyle);
                setText(row, col++, nvl(v.getSpec()), bodyStyle);
                setNumber(row, col++, v.getQuantity(), numberStyle);
                setText(row, col++, nvl(v.getUnit()), bodyStyle);
                setText(row, col++, format(v.getProductionDate()), bodyStyle);
                setText(row, col++, nvl(v.getBatchNo()), bodyStyle);
                setText(row, col++, nvl(v.getShelfLife()), bodyStyle);
                setText(row, col++, format(v.getExpiryDate()), bodyStyle);
                setText(row, col++, nvl(v.getSupplierName()), bodyStyle);
                setText(row, col++, nvl(v.getSupplierAddress()), bodyStyle);
                setText(row, col++, nvl(v.getSupplierPhone()), bodyStyle);
                setText(row, col++, nvl(v.getCertificate()), bodyStyle);
                setNumber(row, col++, v.getUnitPrice(), numberStyle);
                setNumber(row, col++, v.getAmount(), numberStyle);
                setText(row, col++, nvl(v.getWarehouse()), bodyStyle);
                setText(row, col++, nvl(v.getReceiver()), bodyStyle);
                // 级别同时给颜色和文字：黑白打印时颜色会丢，文字不会
                setText(row, col++, nvl(v.getQualityLabel()), bodyStyle);
                setText(row, col++, nvl(v.getStatusLabel()), bodyStyle);
                setText(row, col++, nvl(v.getCreatedBy()), bodyStyle);
                setText(row, col++, "AI".equals(v.getSourceType()) ? "AI 录入" : "手填", bodyStyle);
                setText(row, col, v.getRevision() == null ? "r1" : "r" + v.getRevision(), bodyStyle);
            }

            // ---- 列宽：按内容长度估，避免全挤在一起 ----
            for (int i = 0; i < HEADERS.length; i++) {
                sheet.setColumnWidth(i, widthOf(HEADERS[i]) * 256);
            }

            workbook.write(out);
            log.info("导出台账 {} 条", rows.size());
        }
    }

    // ---------------------------------------------------------- 单元格

    private void setText(Row row, int col, String value, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private void setNumber(Row row, int col, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(col);
        if (value != null) {
            cell.setCellValue(value.doubleValue());
        }
        cell.setCellStyle(style);
    }

    private String format(LocalDate date) {
        return date == null ? "" : date.format(DATE);
    }

    private String nvl(String s) {
        return s == null ? "" : s;
    }

    /**
     * 按字符估宽：中文按 2 个宽度算。
     * 不用 autoSizeColumn，因为它依赖字体度量，在服务器上经常算出过窄的宽度。
     */
    private int widthOf(String header) {
        int width = header.length() * 2;
        return Math.max(10, Math.min(42, width));
    }

    // ---------------------------------------------------------- 样式

    private CellStyle titleStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 16);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle infoStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setFontHeightInPoints((short) 9);
        font.setColor(IndexedColors.GREY_50_PERCENT.getIndex());
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle headerStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setBold(true);
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle bodyStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        Font font = wb.createFont();
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private CellStyle numberStyle(XSSFWorkbook wb) {
        CellStyle style = bodyStyle(wb);
        style.setDataFormat(wb.createDataFormat().getFormat("0.00"));
        style.setAlignment(HorizontalAlignment.RIGHT);
        return style;
    }
}
