package com.minimall.service.sys.support;

import com.minimall.api.sys.dto.MenuTreeNode;
import com.minimall.domain.sys.SysMenu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单树构建(架构文档 5.1、5.2.1)。
 *
 * <p>三个地方都要用到同一套树构建:菜单管理(全量树)、套餐编辑(非平台菜单树)、角色授权(套餐范围内的树)。
 * 抽成一个纯函数既避免三份实现走偏,也让它不依赖 Spring/DB 就能测。
 *
 * <p>父节点不在传入集合里的节点会被当成**根节点**返回(而不是被丢掉):
 * 那样前端至少能看到它、管理员能发现"某个菜单的父级缺失",比静默消失更容易排查。
 */
public final class MenuTreeBuilder {

    private MenuTreeBuilder() {
    }

    public static List<MenuTreeNode> build(List<SysMenu> menus) {
        Map<Long, MutableNode> nodes = new LinkedHashMap<>();
        for (SysMenu menu : menus) {
            nodes.put(menu.getId(), new MutableNode(menu));
        }
        List<MutableNode> roots = new ArrayList<>();
        for (MutableNode node : nodes.values()) {
            MutableNode parent = node.menu.getParentId() == null ? null : nodes.get(node.menu.getParentId());
            if (parent == null) {
                roots.add(node);
            } else {
                parent.children.add(node);
            }
        }
        roots.sort(Comparator
                .comparing((MutableNode node) -> node.menu.getSortOrder() == null ? 0 : node.menu.getSortOrder())
                .thenComparing(node -> node.menu.getId()));
        return roots.stream().map(MutableNode::toNode).toList();
    }

    private static final class MutableNode {

        private final SysMenu menu;
        private final List<MutableNode> children = new ArrayList<>();

        private MutableNode(SysMenu menu) {
            this.menu = menu;
        }

        private MenuTreeNode toNode() {
            children.sort(Comparator
                    .comparing((MutableNode node) -> node.menu.getSortOrder() == null ? 0 : node.menu.getSortOrder())
                    .thenComparing(node -> node.menu.getId()));
            return new MenuTreeNode(
                    menu.getId(),
                    menu.getParentId(),
                    menu.getMenuName(),
                    menu.getMenuType(),
                    menu.getRoutePath(),
                    menu.getPermCode(),
                    menu.getSortOrder(),
                    menu.getStatus(),
                    children.stream().map(MutableNode::toNode).toList());
        }
    }
}
