package com.minimall.mall.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.common.ApiResponse;
import com.minimall.common.PageResult;
import com.minimall.infra.audit.AuditLog;
import com.minimall.mall.api.dto.SeckillActivitySaveRequest;
import com.minimall.mall.api.dto.SeckillActivityView;
import com.minimall.mall.service.SeckillService;
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

/**
 * 商家管理端 - 秒杀(商城设计文档 3.12)。
 *
 * <p>发布与取消是两个独立权限点:发布意味着冻结库存,取消意味着解冻,两者都会立刻改变可售库存,
 * 不能和"编辑"共用一个权限码。
 */
@RestController
@RequestMapping("/mall/admin/seckills")
public class MallSeckillController {

    private final SeckillService seckillService;

    public MallSeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    @GetMapping
    @SaCheckPermission("mall:seckill:list")
    public ApiResponse<PageResult<SeckillActivityView>> page(@RequestParam(required = false) String keyword,
                                                             @RequestParam(required = false) Integer status,
                                                             @RequestParam(defaultValue = "1") int pageNo,
                                                             @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(seckillService.page(keyword, status, pageNo, pageSize));
    }

    @GetMapping("/{activityId}")
    @SaCheckPermission("mall:seckill:list")
    public ApiResponse<SeckillActivityView> detail(@PathVariable Long activityId) {
        return ApiResponse.ok(seckillService.detail(activityId));
    }

    @AuditLog(module = "秒杀", permCode = "mall:seckill:create")
    @PostMapping
    @SaCheckPermission("mall:seckill:create")
    public ApiResponse<Long> create(@Valid @RequestBody SeckillActivitySaveRequest request) {
        return ApiResponse.ok(seckillService.create(request));
    }

    @AuditLog(module = "秒杀", permCode = "mall:seckill:update")
    @PutMapping("/{activityId}")
    @SaCheckPermission("mall:seckill:update")
    public ApiResponse<Void> update(@PathVariable Long activityId,
                                    @Valid @RequestBody SeckillActivitySaveRequest request) {
        seckillService.update(activityId, request);
        return ApiResponse.ok();
    }

    @AuditLog(module = "秒杀", permCode = "mall:seckill:update")
    @DeleteMapping("/{activityId}")
    @SaCheckPermission("mall:seckill:update")
    public ApiResponse<Void> delete(@PathVariable Long activityId) {
        seckillService.delete(activityId);
        return ApiResponse.ok();
    }

    /** 发布:校验并冻结库存,返回冻结总件数。 */
    @AuditLog(module = "秒杀", permCode = "mall:seckill:publish")
    @PostMapping("/{activityId}/publish")
    @SaCheckPermission("mall:seckill:publish")
    public ApiResponse<Integer> publish(@PathVariable Long activityId) {
        return ApiResponse.ok(seckillService.publish(activityId));
    }

    /** 取消:未售出的配额解冻。 */
    @AuditLog(module = "秒杀", permCode = "mall:seckill:cancel")
    @PostMapping("/{activityId}/cancel")
    @SaCheckPermission("mall:seckill:cancel")
    public ApiResponse<Void> cancel(@PathVariable Long activityId) {
        seckillService.cancel(activityId);
        return ApiResponse.ok();
    }
}
