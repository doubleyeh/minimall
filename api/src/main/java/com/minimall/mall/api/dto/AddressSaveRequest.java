package com.minimall.mall.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 收货地址新增/修改(商城设计文档 2)。
 */
public record AddressSaveRequest(
        @NotBlank(message = "收货人不能为空")
        @Size(max = 32, message = "收货人不能超过 32 个字符")
        String receiverName,

        @NotBlank(message = "联系电话不能为空")
        @Size(max = 20, message = "联系电话不能超过 20 个字符")
        String receiverPhone,

        @NotBlank(message = "省份不能为空")
        String province,

        @NotBlank(message = "城市不能为空")
        String city,

        @NotBlank(message = "区县不能为空")
        String district,

        @NotBlank(message = "详细地址不能为空")
        @Size(max = 255, message = "详细地址不能超过 255 个字符")
        String detailAddress,

        /** 是否设为默认地址。设为默认时服务端会把该客户其他地址的默认标记清掉。 */
        Boolean defaultAddress) {
}
