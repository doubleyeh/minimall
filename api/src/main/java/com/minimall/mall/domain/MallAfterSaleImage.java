package com.minimall.mall.domain;

import com.minimall.infra.persistence.BaseTenantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 售后凭证图片(商城设计文档 2)。
 *
 * <p>{@code stage} 区分"申请时提交的凭证"与"商家拒绝收货时的争议凭证":
 * 两者都可能有,且都可能被用于客服仲裁,所以不能只留一份覆盖掉。
 */
@Entity
@Table(name = "mall_after_sale_image")
@Getter
@Setter
public class MallAfterSaleImage extends BaseTenantEntity {

    /** 申请时凭证。 */
    public static final int STAGE_APPLY = 1;
    /** 拒绝收货争议凭证。 */
    public static final int STAGE_DISPUTE = 2;

    @Column(name = "after_sale_id", nullable = false)
    private Long afterSaleId;

    @Column(name = "image_url", nullable = false, length = 255)
    private String imageUrl;

    /** 1-买家 2-商家。 */
    @Column(name = "uploader_type", nullable = false)
    private Integer uploaderType;

    /** 1-申请时凭证 2-拒绝收货争议凭证。 */
    @Column(name = "stage", nullable = false)
    private Integer stage;
}
