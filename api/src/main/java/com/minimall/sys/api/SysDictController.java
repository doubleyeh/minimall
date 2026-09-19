package com.minimall.sys.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.sys.api.dto.DictDataSaveRequest;
import com.minimall.sys.api.dto.DictDataView;
import com.minimall.sys.api.dto.DictItemView;
import com.minimall.sys.api.dto.DictTypeSaveRequest;
import com.minimall.sys.api.dto.DictTypeView;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditLog;
import com.minimall.sys.service.DictService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 字典维护与读取接口(架构文档 5.1)。
 *
 * <p>两类接口刻意放在同一个 Controller 里,但**鉴权强度不同**,不要混淆:
 *
 * <ul>
 *   <li><b>维护类</b>({@code /types/**}、{@code /data/**})带 {@code system:dict:*} 权限码。
 *       这些权限码挂在 {@code is_platform = 1} 的"字典管理"菜单下,而租户角色的授权会被
 *       5.2.1 拦截在套餐范围外,所以**只有平台侧能拿到** —— 与"字典是平台级数据"一致。
 *   <li><b>读取类</b>({@code /values/{dictType}})故意**不加权限码**:它只返回
 *       {@code label}/{@code value} 这种枚举标签,是前端渲染下拉与翻译取值用的,
 *       租户侧每个页面都要用。加权限码会让租户必须被授予"字典"菜单才能显示下拉框,
 *       而业务上没人会把平台配置的维护权限授给租户。它仍然需要登录:
 *       {@code TenantWebFilter} 对非白名单路径会做登录校验(4.9)。
 * </ul>
 */
@RestController
@RequestMapping("/system/dicts")
public class SysDictController {

    private final DictService dictService;

    public SysDictController(DictService dictService) {
        this.dictService = dictService;
    }

    // ---------------------------------------------------------- 字典类型

    @GetMapping("/types")
    @SaCheckPermission("system:dict:list")
    public ApiResponse<PageResult<DictTypeView>> pageTypes(@RequestParam(required = false) String dictType,
                                                           @RequestParam(required = false) String dictName,
                                                           @RequestParam(defaultValue = "1") int pageNo,
                                                           @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(dictService.pageTypes(dictType, dictName, pageNo, pageSize));
    }

    @AuditLog(module = "字典管理", permCode = "system:dict:create")
    @PostMapping("/types")
    @SaCheckPermission("system:dict:create")
    public ApiResponse<Long> createType(@Valid @RequestBody DictTypeSaveRequest request) {
        return ApiResponse.ok(dictService.createType(request));
    }

    @AuditLog(module = "字典管理", permCode = "system:dict:update")
    @PutMapping("/types/{typeId}")
    @SaCheckPermission("system:dict:update")
    public ApiResponse<Void> updateType(@PathVariable Long typeId, @Valid @RequestBody DictTypeSaveRequest request) {
        dictService.updateType(typeId, request);
        return ApiResponse.ok();
    }

    @AuditLog(module = "字典管理", permCode = "system:dict:delete")
    @DeleteMapping("/types/{typeId}")
    @SaCheckPermission("system:dict:delete")
    public ApiResponse<Void> deleteType(@PathVariable Long typeId) {
        dictService.deleteType(typeId);
        return ApiResponse.ok();
    }

    // ---------------------------------------------------------- 字典数据

    @GetMapping("/types/{dictType}/data")
    @SaCheckPermission("system:dict:list")
    public ApiResponse<List<DictDataView>> listData(@PathVariable String dictType) {
        return ApiResponse.ok(dictService.listData(dictType));
    }

    @AuditLog(module = "字典管理", permCode = "system:dict:create")
    @PostMapping("/data")
    @SaCheckPermission("system:dict:create")
    public ApiResponse<Long> createData(@Valid @RequestBody DictDataSaveRequest request) {
        return ApiResponse.ok(dictService.createData(request));
    }

    @AuditLog(module = "字典管理", permCode = "system:dict:update")
    @PutMapping("/data/{dataId}")
    @SaCheckPermission("system:dict:update")
    public ApiResponse<Void> updateData(@PathVariable Long dataId, @Valid @RequestBody DictDataSaveRequest request) {
        dictService.updateData(dataId, request);
        return ApiResponse.ok();
    }

    @AuditLog(module = "字典管理", permCode = "system:dict:delete")
    @DeleteMapping("/data/{dataId}")
    @SaCheckPermission("system:dict:delete")
    public ApiResponse<Void> deleteData(@PathVariable Long dataId) {
        dictService.deleteData(dataId);
        return ApiResponse.ok();
    }

    // ---------------------------------------------------------- 业务端只读

    /**
     * 按类型取下拉项。见类注释:这里**刻意不加权限码**,仅要求登录。
     */
    @GetMapping("/values/{dictType}")
    public ApiResponse<List<DictItemView>> values(@PathVariable String dictType) {
        return ApiResponse.ok(dictService.items(dictType));
    }
}
