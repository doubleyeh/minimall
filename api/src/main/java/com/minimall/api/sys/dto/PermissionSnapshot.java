package com.minimall.api.sys.dto;

import java.util.List;

/**
 * 当前用户的权限快照(架构文档 5.5):前端在收到 403 时调用 {@code GET /auth/permissions} 刷新一次。
 *
 * <p>为什么需要它:菜单快照是登录那一刻的,权限收紧在服务端下一个请求就生效了,
 * 如果前端还拿着旧快照,会出现"按钮还在但点了 403"的误导。
 */
public record PermissionSnapshot(
        List<String> menus,
        List<String> permCodes
) {
}
