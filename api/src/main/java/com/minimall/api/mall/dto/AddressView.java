package com.minimall.api.mall.dto;

/**
 * 收货地址(端上)。
 */
public record AddressView(
        Long id,
        String receiverName,
        String receiverPhone,
        String province,
        String city,
        String district,
        String detailAddress,
        Integer isDefault) {
}
