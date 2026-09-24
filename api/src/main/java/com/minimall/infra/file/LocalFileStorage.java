package com.minimall.infra.file;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 本地磁盘存储(架构文档 7.5)。
 *
 * <p><b>为什么校验文件头而不是只看扩展名/Content-Type</b>:那两个都是客户端说了算 —— 把一段脚本
 * 改名成 {@code .png} 就能过。文件头是内容本身,至少挡住了"扩展名与实际内容不符"这一类。
 * 这里只放行图片,因为当前所有上传场景(商品图、售后凭证)都是图片。
 */
@Component
public class LocalFileStorage implements FileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorage.class);
    private static final DateTimeFormatter DATE_DIR = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    /** 允许的扩展名 → MIME。只收图片:扩大范围前先想清楚"上传一个可执行的东西"意味着什么。 */
    private static final Map<String, String> ALLOWED = Map.of(
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "gif", "image/gif",
            "webp", "image/webp");

    private final FileProperties properties;

    public LocalFileStorage(FileProperties properties) {
        this.properties = properties;
    }

    @Override
    public String store(String originalFilename, byte[] content) {
        String extension = extensionOf(originalFilename);
        String contentType = ALLOWED.get(extension);
        if (contentType == null) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "只支持 png/jpg/jpeg/gif/webp 图片");
        }
        if (content.length == 0) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "文件内容为空");
        }
        if (content.length > properties.maxSizeBytes()) {
            throw new BusinessException(ErrorCode.PARAM_INVALID,
                    "文件超过上限 " + (properties.maxSizeBytes() / 1024 / 1024) + "MB");
        }
        if (!matchesMagic(content, contentType)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "文件内容与图片格式不符");
        }

        // 租户拼进 key:同一个根目录下多个租户的文件互不混杂,排查与清理都好办
        Long tenantId = TenantContext.getTenantId();
        String key = (tenantId == null ? "0" : String.valueOf(tenantId)) + "/"
                + LocalDate.now().format(DATE_DIR) + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extension;
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
        } catch (IOException ex) {
            // 落盘失败要让调用方知道(不能"上传成功但文件没写进去")
            log.error("写入文件失败 key={} root={}", key, properties.rootDir(), ex);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件保存失败");
        }
        return key;
    }

    @Override
    public StoredFile load(String key) {
        Path target = resolve(key);
        if (!Files.isRegularFile(target)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        try {
            return new StoredFile(Files.readAllBytes(target), ALLOWED.getOrDefault(extensionOf(key), "application/octet-stream"));
        } catch (IOException ex) {
            log.error("读取文件失败 key={}", key, ex);
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "文件读取失败");
        }
    }

    /**
     * 把 key 解析成根目录下的绝对路径,**并确认它真的在根目录里**。
     *
     * <p>不校验的话 {@code ../../} 这种 key 能把根目录外的文件读出去(路径穿越)。
     */
    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.contains("..") || key.startsWith("/") || key.contains("\\")) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "非法的文件标识");
        }
        Path root = Paths.get(properties.rootDir()).toAbsolutePath().normalize();
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException(ErrorCode.PARAM_INVALID, "非法的文件标识");
        }
        return target;
    }

    private String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 文件头比对:PNG / JPEG / GIF / WEBP(RIFF....WEBP)。 */
    private boolean matchesMagic(byte[] content, String contentType) {
        return switch (contentType) {
            case "image/png" -> startsWith(content, 0x89, 'P', 'N', 'G');
            case "image/jpeg" -> startsWith(content, 0xFF, 0xD8, 0xFF);
            case "image/gif" -> startsWith(content, 'G', 'I', 'F', '8');
            case "image/webp" -> startsWith(content, 'R', 'I', 'F', 'F') && content.length > 11
                    && content[8] == 'W' && content[9] == 'E' && content[10] == 'B' && content[11] == 'P';
            default -> false;
        };
    }

    private boolean startsWith(byte[] content, int... magic) {
        if (content.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((content[i] & 0xFF) != (magic[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
