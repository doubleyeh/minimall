package com.minimall.mall.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditLog;
import com.minimall.mall.api.dto.CustomerDetailView;
import com.minimall.mall.api.dto.CustomerView;
import com.minimall.mall.api.dto.MemberValueAdjustRequest;
import com.minimall.mall.service.CustomerAdminService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端 - 客户管理(商城设计文档 3.11)。
 *
 * <p>商家侧接口,权限码见方法上的注解(与 V10 迁移里种的菜单一一对应)。
 */
@RestController
@RequestMapping("/mall/admin/customers")
public class MallCustomerController {

    private final CustomerAdminService customerAdminService;

    public MallCustomerController(CustomerAdminService customerAdminService) {
        this.customerAdminService = customerAdminService;
    }

    @GetMapping
    @SaCheckPermission("mall:customer:list")
    public ApiResponse<PageResult<CustomerView>> list(@RequestParam(required = false) String nickname,
                                                      @RequestParam(required = false) String phone,
                                                      @RequestParam(defaultValue = "1") int pageNo,
                                                      @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(customerAdminService.page(nickname, phone, pageNo, pageSize));
    }

    @GetMapping("/{customerId}")
    @SaCheckPermission("mall:customer:detail")
    public ApiResponse<CustomerDetailView> detail(@PathVariable Long customerId) {
        return ApiResponse.ok(customerAdminService.detail(customerId));
    }

    /**
     * 手动调整积分与成长值。
     *
     * <p>单独一个权限码而不是复用"客户列表":能看客户不等于能改别人的资产。
     */
    @AuditLog(module = "客户管理", permCode = "mall:customer:adjust")
    @PostMapping("/{customerId}/adjust")
    @SaCheckPermission("mall:customer:adjust")
    public ApiResponse<Void> adjust(@PathVariable Long customerId,
                                    @Valid @RequestBody MemberValueAdjustRequest request) {
        customerAdminService.adjust(customerId, request);
        return ApiResponse.ok();
    }
}
