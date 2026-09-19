package com.minimall.sys.service;

import com.minimall.sys.api.dto.MenuTreeNode;
import com.minimall.sys.api.dto.PackageMenuSaveRequest;
import com.minimall.sys.api.dto.PackageSaveRequest;
import com.minimall.sys.api.dto.PackageView;
import com.minimall.common.PageResult;

import java.util.List;

/**
 * 套餐管理(架构文档 4.7、4.8、4.8.1)。
 *
 * <p>套餐是平台级数据(4.6.1),只有平台超管能维护。这里有两个容易混淆的"菜单树":
 * <ul>
 *   <li>{@link #grantableMenuTree(Long)}:套餐**能选**哪些菜单——全部 {@code is_platform = 0} 的菜单树</li>
 *   <li>{@code RoleService#grantableMenuTree}:某租户的角色**能勾**哪些菜单——套餐范围内的交集(5.2.1)</li>
 * </ul>
 */
public interface PackageService {

    PageResult<PackageView> page(String packageName, Integer status, int pageNo, int pageSize);

    Long create(PackageSaveRequest request);

    void update(Long packageId, PackageSaveRequest request);

    /**
     * 禁用套餐(不做物理删除,见 5.6):有租户在用时删除会让 {@code tenant.package_id} 指向不存在的记录。
     */
    void disable(Long packageId);

    /** 套餐可选菜单树(全部非平台专用菜单,不做套餐过滤——它本身就是套餐的编辑对象)。 */
    List<MenuTreeNode> grantableMenuTree(Long packageId);

    /** 套餐当前包含的菜单 ID 列表,用于编辑界面回显。 */
    List<Long> menuIds(Long packageId);

    /**
     * 保存套餐菜单——**触发源二(4.8.1),改了马上生效**。
     *
     * <p>实现要点(不要简化掉任何一条):
     * <ol>
     *   <li>修改前先读出旧的菜单 ID 列表作为 {@code M_old}(改完就算不出差异了)</li>
     *   <li>查 {@code SELECT id FROM tenant WHERE package_id = :id} 得到受影响租户</li>
     *   <li>**每个租户各起一个事务**,执行与 4.8 完全相同的差异流程;单租户失败只记日志+告警,不中断循环</li>
     *   <li>每个租户写一条 {@code sys_tenant_package_change}(trigger_type = 2)</li>
     *   <li>统一失效相关租户的权限缓存(5.5)</li>
     * </ol>
     * 校验(5.2.1):不含平台专用菜单;菜单树父链完整。
     */
    void saveMenus(Long packageId, PackageMenuSaveRequest request);

    /**
     * 运维出口:对某个套餐的所有绑定租户重跑一次差异同步。
     *
     * <p>为什么需要它:4.8.1 的同步是"按租户独立事务"的,允许个别租户失败并停留在未同步状态;
     * 同步动作本身幂等(收回是 delete,新增是 insert-if-absent),所以重跑一定能收敛,不需要补偿机制。
     */
    void resync(Long packageId);
}
