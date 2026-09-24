package com.minimall.sys.api.dto;

/**
 * 上传结果(架构文档 7.5)。
 *
 * @param key  存储标识,服务端持有;删除或排查时用它定位
 * @param url  可直接用在 {@code <img src>} 或存进业务表的访问地址
 * @param size 字节数,便于前端提示与排查
 */
public record FileUploadView(
        String key,
        String url,
        long size
) {
}
