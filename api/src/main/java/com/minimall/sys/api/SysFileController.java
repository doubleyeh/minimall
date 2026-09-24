package com.minimall.sys.api;

import com.minimall.common.ApiResponse;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.file.FileProperties;
import com.minimall.infra.file.FileStorage;
import com.minimall.sys.api.dto.FileUploadView;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文件上传与访问(架构文档 7.5)。
 *
 * <p>路径上刻意把两件事分开:
 * <ul>
 *   <li><b>上传</b>{@code POST /file-uploads} —— 挂后台登录态,不单独设权限码:
 *       能进后台的账号本来就能改商品与售后,单独挡上传没有意义;真正的边界是登录态与内容校验</li>
 *   <li><b>访问</b>{@code GET /files/**} —— **公开**:商品图与售后凭证要能在小程序里直接显示,
 *       不能要求带令牌。所以 key 用随机名,安全性靠不可猜</li>
 * </ul>
 * 两者不能放在同一前缀下:白名单是按路径前缀匹配的,放一起等于把上传也一起放开。
 */
@RestController
public class SysFileController {

    private final FileStorage fileStorage;
    private final FileProperties fileProperties;

    public SysFileController(FileStorage fileStorage, FileProperties fileProperties) {
        this.fileStorage = fileStorage;
        this.fileProperties = fileProperties;
    }

    @PostMapping("/file-uploads")
    public ApiResponse<FileUploadView> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择要上传的文件");
        }
        byte[] content = file.getBytes();
        String key = fileStorage.store(file.getOriginalFilename(), content);
        return ApiResponse.ok(new FileUploadView(key, publicUrl(key), content.length));
    }

    /** 按 key 取文件。响应体是原始字节,不能套统一响应体(否则前端 img 拿到的是 JSON)。 */
    @GetMapping("/files/{*key}")
    public ResponseEntity<byte[]> serve(@PathVariable String key) {
        String normalized = key != null && key.startsWith("/") ? key.substring(1) : key;
        FileStorage.StoredFile stored = fileStorage.load(normalized);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(stored.contentType()))
                .body(stored.content());
    }

    private String publicUrl(String key) {
        return fileProperties.publicBaseUrl() + "/files/" + key;
    }
}
