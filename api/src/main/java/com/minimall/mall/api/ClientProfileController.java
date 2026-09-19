package com.minimall.mall.api;

import com.minimall.mall.api.dto.ClientProfileUpdateRequest;
import com.minimall.mall.api.dto.ClientProfileView;
import com.minimall.common.ApiResponse;
import com.minimall.mall.service.ClientProfileService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
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

    public ClientProfileController(ClientProfileService clientProfileService) {
        this.clientProfileService = clientProfileService;
    }

    @GetMapping
    public ApiResponse<ClientProfileView> profile() {
        return ApiResponse.ok(clientProfileService.profile());
    }

    @PutMapping
    public ApiResponse<Void> update(@Valid @RequestBody ClientProfileUpdateRequest request) {
        clientProfileService.update(request);
        return ApiResponse.ok();
    }
}
