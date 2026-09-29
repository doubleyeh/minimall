# 微信小商城 - 业务架构设计

依赖 `architecture.md`(RBAC+多租户基础设施)与脚手架脚本 `V1__init_schema.sql`。本模块所有表继承租户过滤机制,建表脚本是 **`V2__mall_init.sql`**(与脚手架脚本相邻,依赖它、单跑会失败)。

> 关于版本号:脚本已重整为"一个脚手架 + 一个商城"两个基线脚本(`architecture.md` 9.5),
> 商城回到设计稿原本的 `V2` 位置。此前它曾落在 `V4`——那是因为中间几版把种子数据与字典菜单
> 拆成了独立的 `V2`/`V3`,属于过渡状态,不作为约定。
>
> 相比最早那版设计稿有三处落地调整(不改业务语义,只是补齐基础设施硬约束),详见 `V2__mall_init.sql` 头部注释:
> ①所有表统一带 `id/tenant_id/create_time/update_time/create_by/update_by`(租户级实体统一继承 `BaseTenantEntity`,缺列无法落库);
> ②`mall_sku_spec_value` 补 `tenant_id`(否则它是租户隔离链上唯一的缺口);
> ③单列索引改 `(tenant_id, xxx)` 复合索引(租户过滤条件会挂在每一个查询上)。
> 另外 `mall_*` 表**不参与数据权限**、**不实现 `OwnedEntity`**:商城侧操作人是 `mall_customer`,
> 身份记在各自的 `customer_id` 业务列里,不复用 `create_by` 的后台操作人语义。

配套前端文档另出,本文档只覆盖商城端(小程序)+ 商家管理端(复用现有后台脚手架新增 `mall` 目录)的业务设计。

---

## 0. 范围

**本期做**:客户与地址、会员等级与积分(批次过期、滚动成长值升降级、积分抵现)、商品分类与商品、多规格SKU、购物车、订单与订单明细、微信支付/退款、售后(完整流程)、优惠券、满减活动、运费模板、库存流水。

**本期不做**:秒杀、拼团、首页楼层配置化。这三项不建表、不留扩展字段,后续单独立项设计。

**客户账号体系独立于 `sys_user`**,不复用后台管理员账号表,单独建 `mall_customer`。

---

## 1. 技术栈

复用后端 `architecture.md` 9.1 节全部依赖,不新增技术选型。业务模块目录:

```
src/main/java/com/minimall/mall/
  api/      Controller(小程序端前缀 /mall/api、管理端前缀 /mall/admin)+ 各端 DTO
  service/  业务逻辑(接口与 impl 分包)
  domain/   实体与仓储接口;实体以 Client/Mall 前缀区分端,基类继承自 infra.persistence
  infra/    本域专用技术封装:auth(客户端 JWT)、pay(微信支付)、订单号生成
```

> 注意实体基类不在这里:`BaseTenantEntity`/`BaseAuditEntity` 被 sys 与 mall 两边一起继承,
> 因此归属 `com.minimall.infra.persistence`(见 `architecture.md` §3 的分包口径)。
> 把共享基类放进任一业务域,都会让另一个域反向依赖它。

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

**0 元订单**(满减/优惠券把实付打到 0;积分抵现做不到 —— 它最多抵商品金额的 50%):第 7 步之后**立即执行支付成功的处理**(`PayService#settleFreeOrder`),订单直接进"待发货"。放在下单事务里而不是让客户端调一个"确认免支付"的接口 —— 否则任何客户端忘了调,订单就永远卡在待支付:它不走支付渠道,`prepay` 对它直接拒绝,用户既付不掉也只能等超时关单。

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
手动确认与系统自动确认两条路径都要触发,且以订单为幂等键(重复触发只发一次)。具体规则见 3.11。

### 3.5 优惠计算顺序

结算金额计算顺序固定为:

```
商品总额 = Σ(SKU价格 × 数量)
① 先应用满减活动(mall_promotion_full_reduction),按reduction_rule阶梯规则匹配最高档
② 再应用优惠券(mall_coupon),满减后金额判断是否达到优惠券min_order_amount门槛
③ 再应用积分抵现(mall_points_batch,见 3.11):上限是商品总额的 50%,且不超过①②抵完剩下的商品金额
④ 加运费
实付金额 = 商品总额 - ①减免 - ②减免 - ③积分抵现 + 运费
```

同一订单**最多使用一张优惠券**,满减活动可与优惠券叠加,但满减活动之间不叠加(取满足条件里减免金额最大的一个活动)。

**积分抵现的基数与上限都在商品金额上,运费既不参与抵扣也不抬高上限** —— 否则运费也能用积分付,等于免运费。

**这段编排只有一个实现**:`OrderServiceImpl#prepare`,结算试算与真实下单共用(`POST /mall/api/orders/preview` 与 `POST /mall/api/orders`)。端上自己算一遍的话,运费(模板/区域/包邮)、满减(活动+范围+阶梯)、券门槛、积分上限任何一处漂移,方向都是少收钱。

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
- **三个调用方共用同一段"置为已支付"**(`PayServiceImpl#markPaid`):微信回调、0 元订单(见 3.3)、定时查单。共用是必要的 —— 扣减库存、累计销量、写状态日志这三件事漏一件,表现都是"订单看着付了但数据不对"。幂等也在这段里(流水已是成功就返回),所以重复调用不会重复扣库存或重复累计销量
- **订单已被关闭时也要能复活**:买家卡在支付页、订单被超时任务关掉、然后付成功了 —— 钱收都收了,只打日志就是钱货两空,所以 `markPaid` 把订单从 `status=5` 置回 `status=2`。与待支付起点的唯一差别在库存:关单时 `release_locked_stock` 已经退过锁定,所以复活只扣 `stock`(`MallSkuRepository#deductStockOnly`),再动 `locked_stock` 会扣到别的订单头上
- **回调丢了也有兜底**:另有一个任务(每 5 分钟,见第 4 节)扫刚关闭的订单主动调微信查单,确认已支付就走同一条复活路径

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

### 3.11 会员积分与成长值

**积分与成长值是两个独立的量**,不共用一张表:积分可消耗、可过期;成长值只受退款扣回与滚动窗口影响,抵现与过期都不改变它。混在一起的话,成长值的滚动求和会被消耗行污染。

- **记账**:积分按**批次**记(`mall_points_batch`,一笔发放一个批次),可用积分 = Σ(剩余大于 0 且未过期)。消耗按 `expire_time` 升序 FIFO。`mall_customer.points` 是汇总缓存,必须恒等于该值 —— 每次变动成对维护,用例专门断言这条不变量
- **发放**:确认收货时按**实付金额**向下取整,1 元 = 1 积分 + 1 成长值。用实付而不是商品总额:用积分抵现后实付变小,避免"用积分买积分"
- **抵现**:100 积分 = 1 元,**上限是商品金额的 50% 且运费不可抵**(能抵运费等于免运费)。下单时**预扣**——等到支付成功才扣的话,下单到支付之间积分会被另一单用掉,支付时不够就得改订单金额。占用明细记在 `mall_points_use`,订单关闭时按它**退回原批次并沿用原过期时间**(新建批次的话,反复下单再取消就能让积分永久续期)
- **过期**:按批次 FIFO,默认 12 个月(字典 `points_expire_months`)。活跃客户在下单算上限时**懒过期**,定时任务只兜底不活跃客户 —— 否则用户会看到一个包含已过期积分的余额,下单时才发现不能用
- **退款扣回**:售后单到终态时(`AfterSaleServiceImpl#finish`)按 `退款金额 / 订单实付金额` 占比扣回,**扣到 0 为止**(不允许负积分),**换货不扣**(没退钱,交易仍然成立),**同一售后单只扣一次**。一单可有多笔部分退款,每次按占比扣但累计不超过发放值
  - 挂在这里而不是微信退款到账回调里:与库存回补同一时刻、同一事务,语义一致"售后判定成立了";回调只落退款单状态,拿不到业务上下文
  - 分母用**订单实付**而非退款行金额:这样"各笔占比之和 == 1"在整单退光时成立
  - 成长值按同一个"应扣量"扣,而不是按实际扣到的积分:积分可能已被花掉或过期,但成长值代表历史贡献,该降还是要降(同样扣到 0 为止)
  - 未确认收货就退款时该订单还没发过积分,直接跳过 —— 否则会把余额扣成负数
- **等级**:按**近 12 个月滚动成长值**判定(字典 `growth_roll_months`),门槛见 `mall_member_level.growth_threshold`。成长值每次变动后按流水**重算**而不是增量累加 —— 窗口已经滚过的客户做累加会一直偏大。**降级只发生在窗口滚出老值的那一刻**,由每日任务负责

**结算顺序**(接 3.5):商品总额 → 满减 → 优惠券 → **积分抵现** → 加运费。所有金额仍以 `mall_order` 落库值为准,订单上同时留 `points_used` / `points_discount_amount` 两个"当时的值"。

**试算接口** `POST /mall/api/orders/preview`:只算不落单,不占库存、不核销券、不扣积分。结算页靠它展示五项金额与"最多可用多少积分",与真实下单共用 `OrderServiceImpl#prepare`,所以两者逐分一致。**超过上限直接报错而不是静默夹取** —— 静默夹取会让端上预览的价与实际实付对不上。响应里的 `maxRedeemAmount`(上限值多少钱)也由服务端算好,端上不按 100:1 自己除 —— 比例是结算规则的一部分。

**小程序侧的三处**(`miniprogram`):
- `pages/checkout/` —— 结算页。**此前是 0 字节的空壳**(购物车「去结算」与商品详情「立即购买」都指向它,点进去是白屏,即小程序根本下不了单),本次从零实现:收货地址、优惠券、积分抵扣、金额明细(全部取自试算接口)、提交下单、拉起支付;
- `pages/points/` —— 积分明细。变动的**原因文案来自服务端**(`PointsLogView.bizTypeText`),小程序与管理端共用同一份映射;
- `pages/profile/` —— 展示等级名与"还差多少成长值升级"(`ClientProfileView.memberLevelName` / `growthToNextLevel`),并可点进积分明细。等级定义是每租户自建的,一条都没有时统一展示 `MallMemberLevel.DEFAULT_LEVEL_NAME`("普通会员")

**管理端**:
- `/mall/admin/customers` 列表 / 详情 / `POST /{id}/adjust` 手动调整,权限码 `mall:customer:list|detail|adjust`(菜单种子见 V10)。**调整单独一个权限码**:能看客户不等于能改别人的资产
- 调整走 `MemberPointsService#manualAdjust` —— 余额与批次的一致性、等级重算那些不变量只在服务端一处维护,管理端不另抄一份。正数建批次、负数按 FIFO 扣且**扣到 0 为止**;`remark` 必填,手工改动资产要留下"为什么"
- 客户详情**同时给积分与成长值两种流水**:两者分账(见上),只看一种查不出问题。流水的原因文案与管理端共用同一份映射(`PointsLogView.bizTypeText`)

**等级名与"还差多少升级"的解析收在 `MemberLevelService`**:小程序个人中心与管理端客户列表都要用,各写一份的话回退文案迟早不一致 —— 那正是"同一个人在两处看到不同等级名"的来源。

**等级折扣 `discount_rate` 仍不参与结算**(见开放项 2):本次只实现"成长值 → 等级"的升降级。

---

## 4. 定时任务清单

| 任务 | 频率 | 动作 |
|---|---|---|
| 订单超时关闭 | 每分钟 | 扫描 `status=1` 且超过 `order_pay_timeout_minutes` 的订单,置 `status=5, close_reason=1`,释放 `locked_stock` |
| 已关闭订单支付核对 | 每 5 分钟 | 扫描最近关闭(`cancel_time` 在 15 分钟内)、支付流水仍为待支付且已拉起过支付的订单,按商户订单号调微信查单;确认已支付的把订单置回 `status=2` 并扣实库存(兜底回调丢失,见 3.8) |
| 订单自动确认收货 | 每小时 | 扫描 `status=3` 且发货超过 `order_auto_receive_days` 的订单,置 `status=4`,触发积分/成长值增加 |
| 售后商家超时处理 | 每小时 | 扫描 `status=1` 超过72小时的售后单,按类型自动流转到 4 或 2 |
| 售后买家超时退货 | 每小时 | 扫描 `status=2` 超过7天的售后单,置 `status=10` |
| 售后商家超时收货确认 | 每小时 | 扫描 `status=3` 超过10天的售后单,置 `status=4`,触发退款/换货 |
| 优惠券过期清理 | 每天 | 扫描过期未使用的 `mall_coupon_record`,置 `status=3` |
| 积分过期清零 | 每天 4:00 | 把 `mall_points_batch` 里已到期且还有剩余的批次清零,同步扣减 `mall_customer.points` 并写流水(只兜底不活跃客户,活跃客户在下单时已懒过期) |
| 会员等级重算 | 每天 4:20 | 按近 12 个月滚动成长值重算,够不着门槛的**降级**。这是降级的唯一来源(发放/扣回/手动调整都是即时重算) |

---

## 5. 需要确认的开放项

1. 满减活动的适用范围(`scope_type=2/3`)与商品分类/商品的多对多关系,是否需要支持"排除"逻辑(如"全部商品参与,除XX分类外"),本期只支持"包含"逻辑,不支持排除
2. 会员等级折扣(`discount_rate`)与优惠券/满减是否叠加,叠加顺序未定,该字段仍只存不用、不参与结算(等级晋升与降级已实现,见 3.11)
3. 换货场景新SKU库存不足时如何处理(拒绝换货 or 允许超卖),本期默认拒绝,换货申请页需要做库存校验
4. 客户端 token 30天免登录期间,若客户在小程序里主动登出如何处理——本期不做客户端主动登出功能,token过期前无法强制失效,需要时补充一张客户端token黑名单表
