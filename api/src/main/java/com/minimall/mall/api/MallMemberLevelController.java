package com.minimall.mall.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.minimall.mall.api.dto.MemberLevelSaveRequest;
import com.minimall.mall.api.dto.MemberLevelView;
import com.minimall.common.ApiResponse;
import com.minimall.infra.audit.AuditLog;
import com.minimall.mall.service.MemberLevelService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家管理端 - 会员等级(商城设计文档 5.2)。
 */
@RestController
@RequestMapping("/mall/admin/member-levels")
public class MallMemberLevelController {

    private final MemberLevelService memberLevelService;

    public MallMemberLevelController(MemberLevelService memberLevelService) {
        this.memberLevelService = memberLevelService;
    }

    @GetMapping
    @SaCheckPermission("mall:member-level:list")
    public ApiResponse<List<MemberLevelView>> list() {
        return ApiResponse.ok(memberLevelService.list());
    }

    @AuditLog(module = "会员等级", permCode = "mall:member-level:create")
    @PostMapping
    @SaCheckPermission("mall:member-level:create")
    public ApiResponse<Long> create(@Valid @RequestBody MemberLevelSaveRequest request) {
        return ApiResponse.ok(memberLevelService.create(request));
    }

    @AuditLog(module = "会员等级", permCode = "mall:member-level:update")
    @PutMapping("/{levelId}")
    @SaCheckPermission("mall:member-level:update")
    public ApiResponse<Void> update(@PathVariable Long levelId,
                                    @Valid @RequestBody MemberLevelSaveRequest request) {
        memberLevelService.update(levelId, request);
        return ApiResponse.ok();
    }
}
