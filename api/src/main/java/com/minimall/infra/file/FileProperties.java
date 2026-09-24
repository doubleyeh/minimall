package com.minimall.infra.file;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 文件存储配置(架构文档 9.3 的配置约定:值直接写在 yml 里,不用环境变量占位)。
 *
 * @param rootDir       本地存储根目录。生产必须挂持久卷,否则重启即丢
 * @param maxSizeBytes  单文件上限(字节)
 * @param publicBaseUrl 拼在访问路径前的前缀。留空则返回相对路径(同源部署够用);
 *                      小程序端要绝对地址时填域名
 */
@ConfigurationProperties(prefix = "minimall.file")
public record FileProperties(String rootDir, Long maxSizeBytes, String publicBaseUrl) {

    private static final String DEFAULT_ROOT_DIR = "./data/files";
    private static final long DEFAULT_MAX_SIZE_BYTES = 5L * 1024 * 1024;

    public FileProperties {
        rootDir = (rootDir == null || rootDir.isBlank()) ? DEFAULT_ROOT_DIR : rootDir;
        maxSizeBytes = maxSizeBytes == null ? DEFAULT_MAX_SIZE_BYTES : maxSizeBytes;
        publicBaseUrl = publicBaseUrl == null ? "" : publicBaseUrl;
        if (maxSizeBytes <= 0) {
            throw new IllegalArgumentException("minimall.file.max-size-bytes 必须为正数");
        }
    }
}
