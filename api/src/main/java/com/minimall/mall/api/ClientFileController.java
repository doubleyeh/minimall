package com.minimall.mall.api;

import com.minimall.common.ApiResponse;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.file.FileProperties;
import com.minimall.infra.file.FileStorage;
import com.minimall.sys.api.dto.FileUploadView;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 客户端上传(商城设计文档 3.1、架构文档 7.5)。
 *
 * <p>售后凭证要拍照上传,所以小程序端也需要上传入口。身份由 {@code ClientAuthFilter} 用客户端令牌
 * 校验(与 {@code /mall/api/**} 其它接口一致),租户来自上下文,存储实现与后台共用同一个
 * {@link FileStorage} —— 换对象存储时两头一起生效。
 */
@RestController
@RequestMapping("/mall/api/file-uploads")
public class ClientFileController {

    private final FileStorage fileStorage;
    private final FileProperties fileProperties;

    public ClientFileController(FileStorage fileStorage, FileProperties fileProperties) {
        this.fileStorage = fileStorage;
        this.fileProperties = fileProperties;
    }

    @PostMapping
    public ApiResponse<FileUploadView> upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "请选择要上传的文件");
        }
        byte[] content = file.getBytes();
        String key = fileStorage.store(file.getOriginalFilename(), content);
        return ApiResponse.ok(new FileUploadView(key, fileProperties.publicBaseUrl() + "/files/" + key, content.length));
    }
}
