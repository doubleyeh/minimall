package com.minimall.sys.api.dto;

import java.util.List;

/**
 * 当前用户的权限快照(架构文档 5.5):前端在收到 403 时调用 {@code GET /auth/permissions} 刷新一次。
 *
 * <p>为什么需要它:菜单快照是登录那一刻的,权限收紧在服务端下一个请求就生效了,
 * 如果前端还拿着旧快照,会出现"按钮还在但点了 403"的误导。
 *
 * <p>{@code menuTree} 是**导航菜单树**(只有目录与页面,按钮不进树):
 * 前端按它生成动态路由与侧边栏,不再自己硬编码菜单(架构文档 7.4)。
 */
public record PermissionSnapshot(
        List<MenuTreeNode> menuTree,
        List<String> permCodes
) {
}
