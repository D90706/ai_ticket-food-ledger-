package com.example.ledger.upload;

import com.example.ledger.common.ApiResponse;
import com.example.ledger.common.Attachment;
import com.example.ledger.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 附件上传（索证索票照片、检验报告）。
 *
 * 四条安全措施，一条都不能省：
 *   1 白名单扩展名：只收图片和 PDF。不做类型判断就上传，
 *     等于给服务器开了一个"存任意文件"的接口。
 *   2 大小上限：由 spring.servlet.multipart 配置兜住，这里再提示一次。
 *   3 文件名由服务端生成（UUID + 原扩展名）：
 *     用客户端传的名字会有路径穿越（../../）和覆盖同名文件两个问题。
 *   4 存到独立的 uploads 目录，不放进静态资源目录——
 *     否则打包时可能被一起发出去。
 */
@Slf4j
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private static final Set<String> ALLOWED = Set.of("jpg", "jpeg", "png", "webp", "pdf");
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    @Value("${app.upload-dir:./uploads}")
    private String uploadDir;

    @PostMapping
    public ApiResponse<Attachment> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BizException("文件为空");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new BizException("文件超过 10MB，请压缩后再传");
        }

        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = extensionOf(original);
        if (!ALLOWED.contains(ext)) {
            throw new BizException("只支持 jpg / png / webp / pdf，收到的是 ." + ext);
        }

        // 按年月分目录：一年几千张图放一个目录里，ls 都要卡
        String month = LocalDate.now().toString().substring(0, 7);
        Path dir = Path.of(uploadDir, month);
        Files.createDirectories(dir);

        String filename = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        Path target = dir.resolve(filename);
        file.transferTo(target.toAbsolutePath());

        Attachment attachment = new Attachment();
        attachment.setUrl("/uploads/" + month + "/" + filename);
        attachment.setName(original);
        attachment.setSize(file.getSize());
        log.info("附件已保存 {}（{} 字节，原名 {}）", target, file.getSize(), original);
        return ApiResponse.ok(attachment);
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw new BizException("文件没有扩展名，无法判断类型");
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}