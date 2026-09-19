package com.minimall.mall.service.impl;

import com.minimall.api.mall.dto.AddressSaveRequest;
import com.minimall.api.mall.dto.AddressView;
import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.infra.tenant.TenantContext;
import com.minimall.mall.domain.MallCustomerAddress;
import com.minimall.mall.domain.repository.MallCustomerAddressRepository;
import com.minimall.mall.infra.auth.ClientContext;
import com.minimall.mall.service.ClientAddressService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 收货地址实现(商城设计文档 2)。
 *
 * <p>"同一客户至多一条默认地址"这个不变量在库上无法用唯一索引表达(MySQL 没有部分唯一索引),
 * 所以只能靠**所有写入口都走这里**来保证:设置默认时先用一条 UPDATE 清掉该客户的全部默认标记,
 * 再设置目标那条,两步在同一事务内。任何绕过这里直接改 {@code is_default} 的代码都会破坏它。
 */
@Service
@Transactional
public class ClientAddressServiceImpl implements ClientAddressService {

    private static final int MAX_ADDRESS_COUNT = 20;

    private final MallCustomerAddressRepository addressRepository;

    public ClientAddressServiceImpl(MallCustomerAddressRepository addressRepository) {
        this.addressRepository = addressRepository;
    }

    @Override
    public List<AddressView> list() {
        Long customerId = ClientContext.requireCustomerId();
        return addressRepository.findByCustomerIdOrderByIsDefaultDescIdDesc(customerId).stream()
                .map(this::toView)
                .toList();
    }

    @Override
    public Long create(AddressSaveRequest request) {
        Long customerId = ClientContext.requireCustomerId();
        long existing = addressRepository.countByCustomerId(customerId);
        if (existing >= MAX_ADDRESS_COUNT) {
            // 限制条数:地址是"用户手填的自由文本",不设上限会被刷成几万条(接口层面无成本)
            throw new BusinessException(ErrorCode.PARAM_INVALID, "地址数量已达上限(" + MAX_ADDRESS_COUNT + " 条)");
        }
        MallCustomerAddress address = new MallCustomerAddress();
        address.setCustomerId(customerId);
        applyFields(address, request);
        // 第一条地址自动成为默认:否则用户下单时会看到"没有默认地址",还得回列表手动设一次
        boolean asDefault = Boolean.TRUE.equals(request.defaultAddress()) || existing == 0;
        address.setIsDefault(asDefault ? 1 : 0);
        address = addressRepository.save(address);
        if (asDefault) {
            clearOthersDefault(address.getId());
        }
        return address.getId();
    }

    @Override
    public void update(Long addressId, AddressSaveRequest request) {
        MallCustomerAddress address = loadOwned(addressId);
        applyFields(address, request);
        if (Boolean.TRUE.equals(request.defaultAddress())) {
            address.setIsDefault(1);
            clearOthersDefault(addressId);
        }
    }

    @Override
    public void delete(Long addressId) {
        MallCustomerAddress address = loadOwned(addressId);
        Long customerId = ClientContext.requireCustomerId();
        boolean wasDefault = Objects.equals(address.getIsDefault(), 1);
        addressRepository.delete(address);
        if (wasDefault) {
            // 删掉默认地址后要把默认标记挪给另一条:否则用户下单时会「没有默认地址」,
            // 而这个"没有默认"看起来像数据丢失,实际只是标记悬空了
            addressRepository.findByCustomerIdOrderByIsDefaultDescIdDesc(customerId).stream()
                    .findFirst()
                    .ifPresent(next -> next.setIsDefault(1));
        }
    }

    @Override
    public void setDefault(Long addressId) {
        MallCustomerAddress address = loadOwned(addressId);
        clearOthersDefault(addressId);
        address.setIsDefault(1);
    }

    private void clearOthersDefault(Long keepId) {
        Long customerId = ClientContext.requireCustomerId();
        Long tenantId = TenantContext.getTenantId();
        addressRepository.clearDefault(customerId, tenantId);
        // clearDefault 会把目标那条也清掉,所以在调用之后重新设回 1(顺序不能反)
        addressRepository.findById(keepId).ifPresent(address -> address.setIsDefault(1));
    }

    private void applyFields(MallCustomerAddress address, AddressSaveRequest request) {
        address.setReceiverName(request.receiverName());
        address.setReceiverPhone(request.receiverPhone());
        address.setProvince(request.province());
        address.setCity(request.city());
        address.setDistrict(request.district());
        address.setDetailAddress(request.detailAddress());
    }

    private MallCustomerAddress loadOwned(Long addressId) {
        Long customerId = ClientContext.requireCustomerId();
        MallCustomerAddress address = addressRepository.findById(addressId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "地址不存在"));
        if (!Objects.equals(address.getCustomerId(), customerId)) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "地址不存在");
        }
        return address;
    }

    private AddressView toView(MallCustomerAddress address) {
        return new AddressView(address.getId(), address.getReceiverName(), address.getReceiverPhone(),
                address.getProvince(), address.getCity(), address.getDistrict(),
                address.getDetailAddress(), address.getIsDefault());
    }
}
