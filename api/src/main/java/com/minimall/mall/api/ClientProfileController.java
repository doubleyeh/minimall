package com.minimall.mall.api;

import com.minimall.mall.api.dto.ClientProfileUpdateRequest;
import com.minimall.mall.api.dto.ClientProfileView;
import com.minimall.mall.api.dto.PointsLogView;
import com.minimall.common.PageResult;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.service.MemberPointsService;
import com.minimall.common.ApiResponse;
import com.minimall.mall.service.ClientProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小程序端 - 个人中心(商城设计文档 3.1)。
 *
 * <p>需要客户端令牌:资料与订单角标都属于"这个客户自己的"数据。
 */
@RestController
@RequestMapping("/mall/api/profile")
public class ClientProfileController {

    private final ClientProfileService clientProfileService;
    private final MemberPointsService memberPointsService;

    public ClientProfileController(ClientProfileService clientProfileService,
                                   MemberPointsService memberPointsService) {
        this.clientProfileService = clientProfileService;
        this.memberPointsService = memberPointsService;
    }

    @GetMapping
    public ApiResponse<ClientProfileView> profile() {
        return ApiResponse.ok(clientProfileService.profile());
    }

    /**
     * 我的积分明细。最近的在最前。
     *
     * <p>只返回令牌对应的那个客户的流水,不接受 customerId 参数 —— 与订单接口同一个约定。
     */
    @GetMapping("/points-logs")
    public ApiResponse<PageResult<PointsLogView>> pointsLogs(@RequestParam(defaultValue = "1") int pageNo,
                                                            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(memberPointsService.pageLogs(
                ClientContext.requireCustomerId(), pageNo, pageSize));
    }

    @PutMapping
    public ApiResponse<Void> update(@Valid @RequestBody ClientProfileUpdateRequest request) {
        clientProfileService.update(request);
        return ApiResponse.ok();
    }
}
