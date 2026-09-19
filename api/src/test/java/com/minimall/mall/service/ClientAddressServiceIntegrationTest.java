package com.minimall.mall.service;

import com.minimall.common.BusinessException;
import com.minimall.common.ErrorCode;
import com.minimall.mall.api.dto.AddressSaveRequest;
import com.minimall.mall.api.dto.AddressView;
import com.minimall.mall.domain.MallCustomerAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 收货地址服务(商城设计文档 2)。
 *
 * <p>核心是那条**库上无法表达的不变量**:同一客户至多一条默认地址(MySQL 没有部分唯一索引,
 * 所以只能靠所有写入口都走同一段代码来保证)。这里就把每个写入口都验一遍 ——
 * 任何一条路径漏了"先清再设",都会出现两条默认地址,而下单时取哪条就变成随机的。
 */
class ClientAddressServiceIntegrationTest extends MallClientServiceTestBase {

    @Autowired
    private ClientAddressService addressService;

    private static AddressSaveRequest request(String receiver, Boolean asDefault) {
        return new AddressSaveRequest(receiver, "13800000002", "广东省", "深圳市", "南山区",
                "测试路 2 号", asDefault);
    }

    /** 该客户当前有几条默认地址(不变量要求恒为 0 或 1)。 */
    private long defaultCount(Long ownerCustomerId) {
        return inTenant(() -> addressRepository.findByCustomerIdOrderByIsDefaultDescIdDesc(ownerCustomerId).stream()
                .filter(address -> Integer.valueOf(1).equals(address.getIsDefault()))
                .count());
    }

    @Test
    @DisplayName("第一条地址自动成为默认(否则用户下单时会看到「没有默认地址」)")
    void firstAddressBecomesDefault() {
        Long id = asClient(otherCustomerId, () -> addressService.create(request("张三", null)));

        inTenant(() -> {
            MallCustomerAddress address = addressRepository.findById(id).orElseThrow();
            assertThat(address.getIsDefault()).isEqualTo(1);
            assertThat(address.getReceiverName()).isEqualTo("张三");
            assertThat(address.getDetailAddress()).isEqualTo("测试路 2 号");
            return null;
        });
    }

    @Test
    @DisplayName("已有地址时新增不抢默认标记(把默认当隐式行为会让人莫名其妙)")
    void newAddressDoesNotStealDefault() {
        asClient(otherCustomerId, () -> addressService.create(request("张三", null)));
        Long second = asClient(otherCustomerId, () -> addressService.create(request("李四", null)));

        inTenant(() -> {
            assertThat(addressRepository.findById(second).orElseThrow().getIsDefault()).isZero();
            return null;
        });
        assertThat(defaultCount(otherCustomerId)).as("仍然只有一条默认").isEqualTo(1);
    }

    @Test
    @DisplayName("显式要求默认时:新地址成为默认,旧的被清掉(且始终只有一条)")
    void explicitDefaultMovesTheFlag() {
        Long first = asClient(otherCustomerId, () -> addressService.create(request("张三", null)));
        Long second = asClient(otherCustomerId, () -> addressService.create(request("李四", true)));

        inTenant(() -> {
            assertThat(addressRepository.findById(first).orElseThrow().getIsDefault()).isZero();
            assertThat(addressRepository.findById(second).orElseThrow().getIsDefault()).isEqualTo(1);
            return null;
        });
        assertThat(defaultCount(otherCustomerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("setDefault:目标成为唯一默认")
    void setDefaultKeepsSingleDefault() {
        Long first = asClient(otherCustomerId, () -> addressService.create(request("张三", null)));
        Long second = asClient(otherCustomerId, () -> addressService.create(request("李四", null)));

        asClientRun(otherCustomerId, () -> addressService.setDefault(second));

        inTenant(() -> {
            assertThat(addressRepository.findById(second).orElseThrow().getIsDefault()).isEqualTo(1);
            assertThat(addressRepository.findById(first).orElseThrow().getIsDefault()).isZero();
            return null;
        });
        assertThat(defaultCount(otherCustomerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("update 改默认地址同样只留一条")
    void updateToDefaultKeepsSingleDefault() {
        Long first = asClient(otherCustomerId, () -> addressService.create(request("张三", null)));
        Long second = asClient(otherCustomerId, () -> addressService.create(request("李四", null)));

        asClientRun(otherCustomerId, () -> addressService.update(second, request("李四改", true)));

        inTenant(() -> {
            assertThat(addressRepository.findById(first).orElseThrow().getIsDefault()).isZero();
            MallCustomerAddress updated = addressRepository.findById(second).orElseThrow();
            assertThat(updated.getIsDefault()).isEqualTo(1);
            assertThat(updated.getReceiverName()).isEqualTo("李四改");
            return null;
        });
        assertThat(defaultCount(otherCustomerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("删除默认地址后,默认标记挪给剩下的一条(否则看起来像数据丢了)")
    void deletingDefaultMovesFlagToAnother() {
        Long first = asClient(otherCustomerId, () -> addressService.create(request("张三", null)));
        Long second = asClient(otherCustomerId, () -> addressService.create(request("李四", null)));

        asClientRun(otherCustomerId, () -> addressService.delete(first));

        inTenant(() -> {
            assertThat(addressRepository.findById(first)).isEmpty();
            assertThat(addressRepository.findById(second).orElseThrow().getIsDefault())
                    .as("剩下的那条要接住默认标记")
                    .isEqualTo(1);
            return null;
        });
        assertThat(defaultCount(otherCustomerId)).isEqualTo(1);
    }

    @Test
    @DisplayName("删除非默认地址不影响默认标记")
    void deletingNonDefaultKeepsFlag() {
        Long first = asClient(otherCustomerId, () -> addressService.create(request("张三", null)));
        Long second = asClient(otherCustomerId, () -> addressService.create(request("李四", null)));

        asClientRun(otherCustomerId, () -> addressService.delete(second));

        inTenant(() -> {
            assertThat(addressRepository.findById(first).orElseThrow().getIsDefault()).isEqualTo(1);
            return null;
        });
    }

    @Test
    @DisplayName("地址数量达上限被拒(自由文本不设限会被刷成几万条)")
    void createRejectsOverLimit() {
        // 夹具那条地址属于主客户,所以另一个客户要从 0 条建到 20 条才到上限
        for (int i = 0; i < 20; i++) {
            int index = i;
            asClient(otherCustomerId, () -> addressService.create(request("批量" + index, null)));
        }
        assertThat(defaultCount(otherCustomerId)).isEqualTo(1);

        assertThatThrownBy(() -> asClient(otherCustomerId, () -> addressService.create(request("超限", null))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上限");
    }

    @Test
    @DisplayName("列表:默认地址排在最前")
    void listPutsDefaultFirst() {
        Long first = asClient(otherCustomerId, () -> addressService.create(request("张三", null)));
        asClient(otherCustomerId, () -> addressService.create(request("李四", null)));

        List<AddressView> list = asClient(otherCustomerId, () -> addressService.list());

        assertThat(list).hasSize(2);
        assertThat(list.get(0).id()).as("默认地址排最前,下单页默认选中它").isEqualTo(first);
        assertThat(list.get(0).isDefault()).isEqualTo(1);
    }

    @Test
    @DisplayName("别人的地址一律按不存在处理:改不了、删不了、也设不了默认")
    void othersAddressIsInvisible() {
        Long othersAddress = inTenant(() -> newAddress(otherCustomerId, "别人"));

        assertThatThrownBy(() -> asClientRun(customerId,
                () -> addressService.update(othersAddress, request("被改了", null))))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
        assertThatThrownBy(() -> asClientRun(customerId, () -> addressService.delete(othersAddress)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> asClientRun(customerId, () -> addressService.setDefault(othersAddress)))
                .isInstanceOf(BusinessException.class);

        inTenant(() -> {
            MallCustomerAddress address = addressRepository.findById(othersAddress).orElseThrow();
            assertThat(address.getReceiverName()).as("别人的数据一个字都没变").isEqualTo("别人");
            return null;
        });
    }

    @Test
    @DisplayName("不存在的地址:NOT_FOUND")
    void unknownAddressIsNotFound() {
        assertThatThrownBy(() -> asClientRun(customerId, () -> addressService.delete(999999L)))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.NOT_FOUND));
    }
}
