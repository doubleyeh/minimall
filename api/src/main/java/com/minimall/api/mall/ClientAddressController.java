package com.minimall.api.mall;

import com.minimall.api.mall.dto.AddressSaveRequest;
import com.minimall.api.mall.dto.AddressView;
import com.minimall.common.ApiResponse;
import com.minimall.mall.service.ClientAddressService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 小程序端 - 收货地址(商城设计文档 2)。
 */
@RestController
@RequestMapping("/mall/api/addresses")
public class ClientAddressController {

    private final ClientAddressService addressService;

    public ClientAddressController(ClientAddressService addressService) {
        this.addressService = addressService;
    }

    @GetMapping
    public ApiResponse<List<AddressView>> list() {
        return ApiResponse.ok(addressService.list());
    }

    @PostMapping
    public ApiResponse<Long> create(@Valid @RequestBody AddressSaveRequest request) {
        return ApiResponse.ok(addressService.create(request));
    }

    @PutMapping("/{addressId}")
    public ApiResponse<Void> update(@PathVariable Long addressId,
                                    @Valid @RequestBody AddressSaveRequest request) {
        addressService.update(addressId, request);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{addressId}")
    public ApiResponse<Void> delete(@PathVariable Long addressId) {
        addressService.delete(addressId);
        return ApiResponse.ok();
    }

    @PutMapping("/{addressId}/default")
    public ApiResponse<Void> setDefault(@PathVariable Long addressId) {
        addressService.setDefault(addressId);
        return ApiResponse.ok();
    }
}
