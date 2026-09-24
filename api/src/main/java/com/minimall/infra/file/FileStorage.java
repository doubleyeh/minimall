package com.minimall.infra.file;

/**
 * 文件存储(架构文档 7.5)。
 *
 * <p>抽成接口是为了"换实现不改业务":当前只有本地磁盘({@link LocalFileStorage}),
 * 要换对象存储时新写一个实现即可,调用方(上传接口、业务模块)不动。
 *
 * <p>租户隔离在 key 里体现(实现会把当前租户拼进 key),但**读取是公开的**:
 * 商品图、售后凭证这些最终要能在小程序里直接显示,不能要求带令牌。
 * 所以 key 必须不可猜(随机名),不能靠"猜不到路径"以外的任何保护。
 */
public interface FileStorage {

    /**
     * 存一个文件。
     *
     * @param originalFilename 客户端给的文件名,只用来取扩展名与判断类型,不参与落盘路径
     * @return 存储 key(如 {@code 1/2026/09/24/3f2a....png}),后续用它访问
     */
    String store(String originalFilename, byte[] content);

    /**
     * 读一个文件。
     *
     * @param key {@link #store} 返回的 key
     * @return 文件内容与 MIME 类型
     * @throws com.minimall.common.BusinessException key 非法(含路径穿越)或文件不存在时
     */
    StoredFile load(String key);

    record StoredFile(byte[] content, String contentType) {
    }
}
