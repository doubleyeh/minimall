package com.minimall.sys.service.support;

import com.minimall.sys.domain.SysTenantPackageChange;
import com.minimall.sys.domain.repository.SysMenuRepository;
import com.minimall.sys.domain.repository.SysPackageRepository;
import com.minimall.sys.domain.repository.SysTenantPackageChangeRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 套餐变更记录的写入(架构文档 4.8、4.8.1)。
 *
 * <p><b>为什么单独一个类</b>:写记录原本散在 {@code TenantServiceImpl} 与 {@code TenantPackageChangeApplier}
 * 两处,靠调用方"记得写"——漏掉的那条路径就是"改了套餐却没有变更记录"。这里把"一条记录需要哪些字段"
 * (套餐菜单快照、新增/收回差异)和"落库"收在一处,两个调用方都从这里走。
 *
 * <p>它是**叶子**(只依赖仓储与菜单仓储),所以谁都能依赖它,不会和同步逻辑互相依赖。
 */
@Component
public class PackageChangeRecorder {

    private final SysTenantPackageChangeRepository changeRepository;
    private final SysPackageRepository packageRepository;
    private final SysMenuRepository menuRepository;

    public PackageChangeRecorder(SysTenantPackageChangeRepository changeRepository,
                                 SysPackageRepository packageRepository,
                                 SysMenuRepository menuRepository) {
        this.changeRepository = changeRepository;
        this.packageRepository = packageRepository;
        this.menuRepository = menuRepository;
    }

    /**
     * 套餐的菜单集合。
     *
     * <p>{@code packageId} 为 {@code null} 表示"不限",取全部非平台菜单 —— 与建租户时"套餐为空"的语义一致(4.8)。
     */
    public Set<Long> menusOf(Long packageId) {
        if (packageId == null) {
            return menuRepository.findAllNonPlatform().stream()
                    .map(menu -> menu.getId())
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }
        return packageRepository.findById(packageId)
                .map(pkg -> new LinkedHashSet<>(pkg.getMenuIds()))
                .orElseGet(LinkedHashSet::new);
    }

    /** 从 {@code from} 套餐换到 {@code to} 套餐的菜单差异。 */
    public Diff diff(Set<Long> fromMenus, Set<Long> toMenus) {
        Set<Long> added = new LinkedHashSet<>(toMenus);
        added.removeAll(fromMenus);
        Set<Long> revoked = new LinkedHashSet<>(fromMenus);
        revoked.removeAll(toMenus);
        return new Diff(added, revoked);
    }

    /**
     * 落一条变更记录;菜单快照取 {@code newPackageId} **当前**的菜单。
     *
     * <p>预约登记时传进来的 {@code added}/{@code revoked} 是"当前套餐 → 目标套餐"的**预演**差异;
     * 到点真正应用时,同步用的是那一刻重算的实际差异(记录里的这份只供变更历史展示)。
     */
    public SysTenantPackageChange record(Long tenantId, int triggerType, Long oldPackageId, Long newPackageId,
                                       LocalDate effectiveDate, Set<Long> added, Set<Long> revoked) {
        SysTenantPackageChange change = new SysTenantPackageChange();
        change.setTenantId(tenantId);
        change.setTriggerType(triggerType);
        change.setOldPackageId(oldPackageId);
        change.setNewPackageId(newPackageId);
        change.setEffectiveDate(effectiveDate);
        change.setPackageMenuIds(toJsonArray(menusOf(newPackageId)));
        change.setAddedMenuIds(toJsonArray(added));
        change.setRevokedMenuIds(toJsonArray(revoked));
        return changeRepository.save(change);
    }

    /** 把 ID 集合拼成 JSON 数组字符串(库里的既有格式)。 */
    public static String toJsonArray(java.util.Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return "[]";
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(",", "[", "]"));
    }

    /** 解析 {@link #toJsonArray} 写出来的字符串;空值/坏值一律返回空列表,不让展示层因为脏数据崩掉。 */
    public static List<Long> parseIds(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        String body = jsonArray.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return List.of();
        }
        List<Long> ids = new ArrayList<>();
        for (String part : body.split(",")) {
            try {
                ids.add(Long.valueOf(part.trim()));
            } catch (NumberFormatException ex) {
                return List.of();
            }
        }
        return ids;
    }

    /** 一次套餐变更的菜单差异。 */
    public record Diff(Set<Long> added, Set<Long> revoked) {
    }
}
