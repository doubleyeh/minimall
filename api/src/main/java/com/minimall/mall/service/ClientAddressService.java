package com.minimall.mall.service;

import com.minimall.mall.api.dto.AddressSaveRequest;
import com.minimall.mall.api.dto.AddressView;

import java.util.List;

/**
 * 收货地址(商城设计文档 2)。
 *
 * <p>所有操作都限定在**当前登录客户**:地址属于个人隐私数据,不接受客户端传入 customerId。
 */
public interface ClientAddressService {

    List<AddressView> list();

    Long create(AddressSaveRequest request);

    void update(Long addressId, AddressSaveRequest request);

    void delete(Long addressId);

    void setDefault(Long addressId);
}
