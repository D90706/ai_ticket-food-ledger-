package com.example.ledger.common;

import lombok.Data;

/**
 * 附件（索证索票照片、检验报告扫描件）。
 *
 * 只存 URL、原文件名、大小三样：
 * 文件本身在磁盘上，数据库不背二进制——
 * 台账查询要快，不该被几 MB 的图片拖住。
 */
@Data
public class Attachment {

    private String url;
    private String name;
    private long size;
}