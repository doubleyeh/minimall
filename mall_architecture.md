# 微信小商城 - 业务架构设计

依赖 `architecture.md`(RBAC+多租户基础设施)与 `rbac_tenant_schema.sql`。本模块所有表继承租户过滤机制,建表脚本 `mall_schema.sql` 已落地为 **`V4__mall_init.sql`**。

> 关于版本号:设计稿原写"作为 V2 接在 V1 之后",但 `V2` 已被种子数据(`V2__seed_platform_data.sql`)占用,
> 后续又新增了字典菜单的 `V3`。按 `architecture.md` 9.5"已发布脚本不可变",商城建表只能新开版本,
> 所以实际版本是 **V4**。
>
> 相比设计稿有三处落地调整(不改业务语义,只是补齐基础设施硬约束),详见 `V4__mall_init.sql` 头部注释:
> ①所有表统一带 `id/tenant_id/create_time/update_time/create_by/update_by`(租户级实体统一继承 `BaseTenantEntity`,缺列无法落库);
> ②`mall_sku_spec_value` 补 `tenant_id`(否则它是租户隔离链上唯一的缺口);
> ③单列索引改 `(tenant_id, xxx)` 复合索引(租户过滤条件会挂在每一个查询上)。
> 另外 `mall_*` 表**不参与数据权限**、**不实现 `OwnedEntity`**:商城侧操作人是 `mall_customer`,
> 身份记在各自的 `customer_id` 业务列里,不复用 `create_by` 的后台操作人语义。

配套前端文档另出,本文档只覆盖商城端(小程序)+ 商家管理端(复用现有后台脚手架新增 `mall` 目录)的业务设计。

---

## 0. 范围

**本期做**:客户与地址、会员等级积分(仅字段)、商品分类与商品、多规格SKU、购物车、订单与订单明细、微信支付/退款、售后(完整流程)、优惠券、满减活动、运费模板、库存流水。

**本期不做**:秒杀、拼团、首页楼层配置化。这三项不建表、不留扩展字段,后续单独立项设计。

**客户账号体系独立于 `sys_user`**,不复用后台管理员账号表,单独建 `mall_customer`。

---

## 1. 技术栈

复用后端 `architecture.md` 9.1 节全部依赖,不新增技术选型。业务模块目录:

```
src/main/java/.../mall/
  api/          小程序端Controller(前缀 /mall/api)与商家管理端Controller(前缀 /mall/admin)分开
  service/
  domain/       实体类,全部继承 BaseTenantEntity
  infra/        微信支付SDK封装、库存扣减的并发控制
```

管理端页面按现有前端脚手架规范,在 `views/mall/` 下新增,菜单数据通过 `sys_menu` 配置,权限码格式沿用 `模块:资源:操作`(如 `mall:goods:delete`)。

---

## 2. 领域模型概览

```
mall_customer ─┬─ mall_customer_address
               ├─ mall_cart ─── mall_sku
               ├─ mall_order ─┬─ mall_order_item ─── mall_sku ─── mall_goods ─── mall_goods_category
               │              ├─ mall_order_status_log
               │              ├─ mall_wx_payment
               │              └─ mall_coupon_record(使用)
               ├─ mall_coupon_record ─── mall_coupon
               ├─ mall_points_log
               └─ mall_after_sale ─┬─ mall_after_sale_log
                                   ├─ mall_after_sale_image
                                   └─ mall_wx_refund

mall_goods ─┬─ mall_goods_image
            ├─ mall_goods_spec ─── mall_goods_spec_value
            ├─ mall_sku ─── mall_sku_spec_value ─── mall_goods_spec_value
            ├─ mall_goods_review
            └─ mall_stock_log(挂在sku上)

mall_freight_template ─── mall_freight_template_rule ─── mall_goods(引用)
mall_promotion_full_reduction ─── mall_promotion_full_reduction_scope
```

---

## 3. 关键设计规则

### 3.1 客户账号与登录

- 客户登录态与后台管理员(Sa-Token session)**分离**,不复用。管理端会话超时是分钟/小时级,客户端小程序免登录时长要求是天/周级,两者耦合会互相牵制
- 登录接口:`POST /mall/api/auth/wx-login`,请求体 `{ code }`(小程序 `wx.login()` 返回的临时登录凭证)
- 处理顺序:
  1. 用 `code` 调用微信 `code2Session` 接口换取 `openid`(和可能的 `unionid`)
  2. 按 `(tenant_id, openid)` 查 `mall_customer`,不存在则创建新记录(`register_time` 记当前时间)
  3. 签发客户端专用 token(JWT,不经过 Sa-Token),payload 至少包含 `customerId`、`tenantId`
  4. 更新 `last_login_time`
- 客户端 token 校验走独立的拦截器/过滤器,与后台 `TenantWebFilter`(见后端 4.2 节)是两套互不影响的过滤链,但**都要做租户隔离**,客户端过滤器同样要把 `tenantId` 塞进当前请求上下文,后续所有 `mall_*` 表查询走同一套 Hibernate Filter 机制
- token 过期策略:有效期 30 天,过期后前端静默重新调用 `wx-login`(小程序场景 `code` 可重复获取,不需要传统的 refresh token 机制)

### 3.2 商品与SKU

- 商品的 `sale_price_min`/`sale_price_max`/`total_stock`/`sale_count` 是冗余汇总字段,任何 SKU 的价格/库存变动,必须在同一事务内同步更新到 `mall_goods` 对应字段
- 商品下架(`status = 0`)不物理删除 SKU 和历史订单关联,已下单的订单明细走快照字段展示,不受商品下架影响
- SKU 的 `stock` 与 `locked_stock` 分离:**可售库存 = `stock - locked_stock`**,列表页/详情页展示的库存按可售库存计算,不能直接展示 `stock`

### 3.3 下单流程与库存锁定

```
1. 校验购物车/直接购买的SKU:状态正常、可售库存(stock-locked_stock) >= 购买数量
2. 计算优惠:命中的满减规则 + 使用的优惠券(若有),按 3.5 顺序计算
3. 计算运费:按商品的freight_template_id匹配收货地址所在区域,按3.7规则计算,取订单内所有商品运费之和(不做合并包裹优化)
4. 落 mall_order + mall_order_item(全部走快照字段)
5. 对涉及的每个SKU:locked_stock += 购买数量,同时写一条mall_stock_log(change_type=1)
6. 若使用了优惠券:对应mall_coupon_record.status改为2,order_id回填
7. 拉起微信支付统一下单,创建mall_wx_payment(pay_status=0)
```

第4-7步必须在同一数据库事务内完成(不含微信支付统一下单这一步的网络调用,网络调用放事务外,失败则整体回滚订单和库存锁定)。

### 3.4 订单状态机

| status | 含义 |
|---|---|
| 1 | 待支付 |
| 2 | 待发货(已支付) |
| 3 | 待收货(已发货) |
| 4 | 已完成(已确认收货) |
| 5 | 已取消(未支付关闭:超时/买家取消/商家取消,由 `close_reason` 区分) |
| 6 | 售后中(存在进行中的售后单,该状态与售后单状态联动,见3.9) |

流转:
```
1 --支付回调成功--> 2
1 --超时15分钟未支付--> 5(close_reason=1,系统自动关闭,同时释放locked_stock,写mall_stock_log change_type=2)
1 --买家主动取消--> 5(close_reason=2,同上释放库存)
2 --商家发货--> 3
2 --商家取消(未发货,需先协商退款)--> 5(close_reason=3,触发全额退款流程)
3 --买家确认收货 / 系统自动确认(签收后15天未操作)--> 4
2/3/4 --产生售后单--> 6,售后终态后按售后结果回到对应状态或保持4
```

**支付超时15分钟**、**收货后自动确认15天**均为可配置参数(建 `sys_dict_data`,`dict_type` 分别为 `order_pay_timeout_minutes`、`order_auto_receive_days`),不写死在代码里。

订单支付成功和确认收货是两个必须触发积分/成长值增加的时点,二选一:**确认收货(status=4)时触发**,避免下单后立刻退款还要扣回积分的场景增多。

### 3.5 优惠计算顺序

结算金额计算顺序固定为:

```
商品总额 = Σ(SKU价格 × 数量)
① 先应用满减活动(mall_promotion_full_reduction),按reduction_rule阶梯规则匹配最高档
② 再应用优惠券(mall_coupon),满减后金额判断是否达到优惠券min_order_amount门槛
③ 加运费
实付金额 = 商品总额 - ①减免 - ②减免 + 运费
```

同一订单**最多使用一张优惠券**,满减活动可与优惠券叠加,但满减活动之间不叠加(取满足条件里减免金额最大的一个活动)。

### 3.6 订单号规则

`order_no` 格式:`日期(yyyyMMdd,8位) + 当日序列号(Redis INCR按天重置,补零至8位)`,共16位数字字符串。`after_sale_no`/`out_refund_no` 同规则,前缀分别替换为区分标识(如售后单在16位前加固定前缀`T`)。

### 3.7 运费计算

```
1. 按订单商品的freight_template_id分组(同一订单可能有多个模板/包邮商品混合)
2. 每组按收货地址匹配对应region规则(未匹配到特定region的用region='ALL'兜底规则)
3. 该组商品总金额 >= free_shipping_amount时该组运费为0
4. 否则:按charge_type取该组商品的件数或重量之和,首件(重)按first_fee计,超出部分按additional_unit为步长向上取整乘additional_fee
5. 订单运费 = 各组运费之和
```

### 3.8 微信支付回调幂等

- `mall_wx_payment.wx_transaction_id` 唯一约束,回调处理前先按 `wx_transaction_id` 查是否已存在成功记录,存在则直接返回成功(不重复处理业务逻辑),防止微信重复推送导致重复触发发货前置状态变更
- 回调处理顺序:验签 → 查 `mall_wx_payment` 幂等判断 → 更新 `pay_status=1` 及 `callback_time` → 更新 `mall_order.status=2` 及 `pay_time` → 各 SKU `stock -= 数量, locked_stock -= 数量`(写 `mall_stock_log change_type=3`)→ 增加 `mall_goods.sale_count`
- 支付失败/超时关闭的回调:`pay_status=2`,不改订单状态(订单状态由超时任务或用户取消驱动,不依赖支付失败回调)

### 3.9 售后完整流程

**状态定义**

| status | 含义 | 终态 |
|---|---|---|
| 1 | 待商家处理 | 否 |
| 2 | 商家已同意,待买家退货(仅退货退款/换货) | 否 |
| 3 | 买家已退货,待商家确认收货 | 否 |
| 4 | 退款成功/换货完成 | 是 |
| 5 | 商家拒绝申请 | 否 |
| 6 | 商家拒绝收货 | 否 |
| 7 | 平台客服介入中 | 否 |
| 8 | 客服仲裁通过 | 是 |
| 9 | 客服仲裁驳回 | 是 |
| 10 | 已关闭(撤销/超时) | 是 |

**流转(仅退款,`after_sale_type=1`)**
```
1 --商家同意--> 4(直接退款)
1 --商家拒绝--> 5 --买家申请客服介入--> 7 --客服仲裁--> 8/9
5 --买家撤销--> 10
1 --超时72小时商家未处理--> 4(系统自动同意)
```

**流转(退货退款/换货,`after_sale_type=2/3`)**
```
1 --商家同意--> 2 --买家提交退货物流--> 3
1 --商家拒绝--> 5(同上仅退款分支)
1 --超时72小时商家未处理--> 2(系统自动同意)
2 --超时7天买家未退货--> 10(系统自动关闭)
3 --商家确认收货--> 4(触发退款,或换货场景触发重新发货)
3 --商家拒绝收货--> 6 --买家申请客服介入--> 7 / 买家撤销--> 10
3 --超时10天商家未处理--> 4(系统自动确认收货)
7 --客服仲裁--> 8/9
```

三个超时阈值(72小时商家处理、7天买家退货、10天商家收货)建 `sys_dict_data`(`dict_type=after_sale_timeout`)配置化。

**申请入口限制**:
- `after_sale_type=1`(仅退款)仅允许订单 `status=2`(待发货)时申请;`status=3/4` 需要走退货退款
- 已存在进行中售后单(未到终态)的订单明细,不允许重复发起

**退款金额**:商家处理时可下调 `refund_amount`(不超过该订单明细行 `total_amount`),不可上调;下调操作记入 `mall_after_sale_log.remark`

**换货**:仅支持同 SKU 价位内换其他规格,不支持补差价;不同价位换货本期不支持

**客服介入(status=7)**:本期没有独立客服角色,由商家(或超管)在管理端处理,操作人 `operator_type` 仍记 `4-平台客服`,为将来接入独立客服角色预留字段含义不变

**终态触发的下游动作**:
- status=4(退货退款/仅退款场景):触发 `mall_wx_refund` 记录创建,发起微信退款
- status=4(换货场景):不产生退款记录,更新 `reship_logistics_*` 字段,原SKU库存回补(`mall_stock_log change_type=4`),新SKU库存扣减
- status=8:与 status=4 的退款/换货动作相同,由客服仲裁触发
- status=9/10:不产生任何库存/资金变动

**售后期间订单状态**:订单进入 `mall_after_sale.status` 非终态时,`mall_order.status` 置为 6(售后中);售后单到终态后,`mall_order.status` 按售后结果回退(仲裁驳回/关闭则恢复售后发起前的状态,退款/换货成功则视具体场景:仅退款不影响订单完成流程,整单退货退款则订单终态为已取消)

### 3.10 优惠券与满减

- 优惠券领取:`mall_coupon.received_count` 与 `total_count` 比较,达到上限拒绝领取;领取时校验该客户在 `mall_coupon_record` 中已有记录数是否达到 `per_customer_limit`
- 领取和使用是两个独立动作,领取只产生 `mall_coupon_record`(`status=1`),下单使用时才关联 `order_id` 并置 `status=2`
- 优惠券过期:定时任务扫描 `valid_end_time` 已过且 `status=1` 的记录,批量置为 `status=3`,不做实时判断兜底(下单时仍需再判断一次 `valid_end_time`,双重保险,不能完全依赖定时任务的执行时机)
- 满减活动的 `reduction_rule` 存 JSON,应用层解析后按金额从高到低匹配第一个满足的阶梯

---

## 4. 定时任务清单

| 任务 | 频率 | 动作 |
|---|---|---|
| 订单超时关闭 | 每分钟 | 扫描 `status=1` 且超过 `order_pay_timeout_minutes` 的订单,置 `status=5, close_reason=1`,释放 `locked_stock` |
| 订单自动确认收货 | 每小时 | 扫描 `status=3` 且发货超过 `order_auto_receive_days` 的订单,置 `status=4`,触发积分/成长值增加 |
| 售后商家超时处理 | 每小时 | 扫描 `status=1` 超过72小时的售后单,按类型自动流转到 4 或 2 |
| 售后买家超时退货 | 每小时 | 扫描 `status=2` 超过7天的售后单,置 `status=10` |
| 售后商家超时收货确认 | 每小时 | 扫描 `status=3` 超过10天的售后单,置 `status=4`,触发退款/换货 |
| 优惠券过期清理 | 每天 | 扫描过期未使用的 `mall_coupon_record`,置 `status=3` |

---

## 5. 需要确认的开放项

1. 满减活动的适用范围(`scope_type=2/3`)与商品分类/商品的多对多关系,是否需要支持"排除"逻辑(如"全部商品参与,除XX分类外"),本期只支持"包含"逻辑,不支持排除
2. 会员等级折扣(`discount_rate`)与优惠券/满减是否叠加,叠加顺序未定,本期字段已建但计算逻辑不实现,等业务规则明确后再排期
3. 换货场景新SKU库存不足时如何处理(拒绝换货 or 允许超卖),本期默认拒绝,换货申请页需要做库存校验
4. 客户端 token 30天免登录期间,若客户在小程序里主动登出如何处理——本期不做客户端主动登出功能,token过期前无法强制失效,需要时补充一张客户端token黑名单表
