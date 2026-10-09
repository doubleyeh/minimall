-- ============================================================
-- 开发库 minimall_dev 的快照(含演示商户与商品数据)
--
-- 导出:
--   mysqldump --databases minimall_dev --single-transaction --default-character-set=utf8mb4 --set-gtid-purged=OFF
-- 导入:
--   mysql -uroot -p < doc/minimall_dev.sql
--   (文件自带 CREATE DATABASE 与 USE,不需要先手工建库)
--
-- 里面包含 flyway_schema_history,所以导入后启动应用不会再跑迁移。
-- 代价是它锁定了脚本版本:db/migration 之后再有改动,这份快照的校验和
-- 就对不上(启动报 Migration checksum mismatch)。要刷新这份文件时,
-- 删库重建、让 Flyway 重新迁移,再重新导出覆盖它。
--
-- 账号(快照时刻):
--   平台超管  tenantCode=platform  admin      / Minimall@2026
--   演示商户  tenantCode=demo      shopadmin  / Demo@123456
-- ============================================================
-- MySQL dump 10.13  Distrib 8.4.9, for Win64 (x86_64)
--
-- Host: 127.0.0.1    Database: minimall_dev
-- ------------------------------------------------------
-- Server version	8.4.9

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

--
-- Current Database: `minimall_dev`
--

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `minimall_dev` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `minimall_dev`;

--
-- Table structure for table `flyway_schema_history`
--

DROP TABLE IF EXISTS `flyway_schema_history`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `flyway_schema_history` (
  `installed_rank` int NOT NULL,
  `version` varchar(50) DEFAULT NULL,
  `description` varchar(200) NOT NULL,
  `type` varchar(20) NOT NULL,
  `script` varchar(1000) NOT NULL,
  `checksum` int DEFAULT NULL,
  `installed_by` varchar(100) NOT NULL,
  `installed_on` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `execution_time` int NOT NULL,
  `success` tinyint(1) NOT NULL,
  PRIMARY KEY (`installed_rank`),
  KEY `flyway_schema_history_s_idx` (`success`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `flyway_schema_history`
--

LOCK TABLES `flyway_schema_history` WRITE;
/*!40000 ALTER TABLE `flyway_schema_history` DISABLE KEYS */;
INSERT INTO `flyway_schema_history` VALUES (1,'1','init schema','SQL','V1__init_schema.sql',1439801103,'root','2026-10-09 05:38:30',389,1),(2,'2','mall init','SQL','V2__mall_init.sql',1319177831,'root','2026-10-09 05:38:31',781,1),(3,'3','oper log menu','SQL','V3__oper_log_menu.sql',-893157021,'root','2026-10-09 05:38:31',7,1),(4,'4','tenant expire perm','SQL','V4__tenant_expire_perm.sql',2126323441,'root','2026-10-09 05:38:31',6,1),(5,'5','tenant expire notice dict','SQL','V5__tenant_expire_notice_dict.sql',198200310,'root','2026-10-09 05:38:31',5,1),(6,'6','password history','SQL','V6__password_history.sql',1066028208,'root','2026-10-09 05:38:31',19,1),(7,'7','tenant purge','SQL','V7__tenant_purge.sql',-1253185177,'root','2026-10-09 05:38:31',17,1),(8,'8','member points growth','SQL','V8__member_points_growth.sql',1168707500,'root','2026-10-09 05:38:31',113,1),(9,'9','order points redeem','SQL','V9__order_points_redeem.sql',-132554866,'root','2026-10-09 05:38:31',56,1),(10,'10','member customer menu','SQL','V10__member_customer_menu.sql',-1491692616,'root','2026-10-09 05:38:31',5,1),(11,'11','task run log','SQL','V11__task_run_log.sql',404204417,'root','2026-10-09 05:38:31',26,1),(12,'12','stock warn menu dict','SQL','V12__stock_warn_menu_dict.sql',-380530786,'root','2026-10-09 05:38:31',9,1),(13,'13','sales stat menu','SQL','V13__sales_stat_menu.sql',326850365,'root','2026-10-09 05:38:31',5,1);
/*!40000 ALTER TABLE `flyway_schema_history` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_after_sale`
--

DROP TABLE IF EXISTS `mall_after_sale`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_after_sale` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `after_sale_no` varchar(32) NOT NULL COMMENT '对外展示单号',
  `order_id` bigint NOT NULL,
  `order_item_id` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `after_sale_type` int NOT NULL COMMENT '1-仅退款 2-退货退款 3-换货',
  `status` int NOT NULL COMMENT '见3.9售后状态机,1/2/3/4/5/6/7/8/9/10',
  `apply_reason` varchar(64) NOT NULL,
  `apply_desc` varchar(500) DEFAULT NULL,
  `refund_amount` decimal(10,2) NOT NULL COMMENT '申请退款金额,商家审核时可改小,不可改大,改动记入日志',
  `reject_reason` varchar(255) DEFAULT NULL COMMENT '商家拒绝申请或拒绝收货均用此字段,靠status区分',
  `return_logistics_company` varchar(64) DEFAULT NULL,
  `return_logistics_no` varchar(64) DEFAULT NULL,
  `return_time` datetime DEFAULT NULL,
  `receive_confirm_time` datetime DEFAULT NULL,
  `reship_logistics_company` varchar(64) DEFAULT NULL COMMENT '换货场景商家重新发货',
  `reship_logistics_no` varchar(64) DEFAULT NULL,
  `arbitration_time` datetime DEFAULT NULL,
  `arbitration_remark` varchar(500) DEFAULT NULL,
  `finish_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_after_sale_no` (`tenant_id`,`after_sale_no`),
  KEY `idx_tenant_order` (`tenant_id`,`order_id`),
  KEY `idx_tenant_order_item` (`tenant_id`,`order_item_id`),
  KEY `idx_tenant_status_update` (`tenant_id`,`status`,`update_time`) COMMENT '售后超时任务按(status, 最后变动时间)扫描'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后单主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_after_sale`
--

LOCK TABLES `mall_after_sale` WRITE;
/*!40000 ALTER TABLE `mall_after_sale` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_after_sale` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_after_sale_image`
--

DROP TABLE IF EXISTS `mall_after_sale_image`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_after_sale_image` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `after_sale_id` bigint NOT NULL,
  `image_url` varchar(255) NOT NULL,
  `uploader_type` int NOT NULL COMMENT '1-买家 2-商家',
  `stage` int NOT NULL COMMENT '1-申请时凭证 2-拒绝收货争议凭证',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_after_sale` (`tenant_id`,`after_sale_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后凭证图片';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_after_sale_image`
--

LOCK TABLES `mall_after_sale_image` WRITE;
/*!40000 ALTER TABLE `mall_after_sale_image` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_after_sale_image` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_after_sale_log`
--

DROP TABLE IF EXISTS `mall_after_sale_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_after_sale_log` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `after_sale_id` bigint NOT NULL,
  `from_status` int DEFAULT NULL,
  `to_status` int NOT NULL,
  `operator_type` int NOT NULL COMMENT '1-买家 2-商家 3-系统自动 4-平台客服',
  `operator_id` bigint DEFAULT NULL,
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_after_sale` (`tenant_id`,`after_sale_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='售后状态流转记录,与mall_order_status_log是两条独立审计链';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_after_sale_log`
--

LOCK TABLES `mall_after_sale_log` WRITE;
/*!40000 ALTER TABLE `mall_after_sale_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_after_sale_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_cart`
--

DROP TABLE IF EXISTS `mall_cart`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_cart` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `sku_id` bigint NOT NULL,
  `quantity` int NOT NULL,
  `selected` int NOT NULL DEFAULT '1' COMMENT '结算页是否勾选,1-选中',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_customer_sku` (`tenant_id`,`customer_id`,`sku_id`) COMMENT '同一客户同一SKU只有一行,加购时数量累加'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='购物车';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_cart`
--

LOCK TABLES `mall_cart` WRITE;
/*!40000 ALTER TABLE `mall_cart` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_cart` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_coupon`
--

DROP TABLE IF EXISTS `mall_coupon`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_coupon` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `coupon_name` varchar(64) NOT NULL,
  `coupon_type` int NOT NULL COMMENT '1-满减券 2-折扣券 3-无门槛现金券',
  `discount_amount` decimal(10,2) DEFAULT NULL COMMENT 'coupon_type=1/3时使用,减免金额',
  `discount_rate` decimal(3,2) DEFAULT NULL COMMENT 'coupon_type=2时使用,如0.9表示9折',
  `min_order_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '满多少可用,无门槛券填0',
  `total_count` int NOT NULL COMMENT '发放总量',
  `received_count` int NOT NULL DEFAULT '0' COMMENT '已领取数量,冗余字段防止总量超发',
  `per_customer_limit` int NOT NULL DEFAULT '1' COMMENT '每人限领数量',
  `valid_start_time` datetime NOT NULL,
  `valid_end_time` datetime NOT NULL,
  `status` int NOT NULL DEFAULT '1' COMMENT '0-已停用 1-进行中',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='优惠券定义';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_coupon`
--

LOCK TABLES `mall_coupon` WRITE;
/*!40000 ALTER TABLE `mall_coupon` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_coupon` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_coupon_record`
--

DROP TABLE IF EXISTS `mall_coupon_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_coupon_record` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `coupon_id` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `status` int NOT NULL DEFAULT '1' COMMENT '1-未使用 2-已使用 3-已过期',
  `order_id` bigint DEFAULT NULL COMMENT '使用时关联的订单,未使用为空',
  `receive_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `use_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_customer` (`tenant_id`,`customer_id`),
  KEY `idx_tenant_coupon` (`tenant_id`,`coupon_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='优惠券领取/使用记录,与定义表分开,一张券定义对应多条领取记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_coupon_record`
--

LOCK TABLES `mall_coupon_record` WRITE;
/*!40000 ALTER TABLE `mall_coupon_record` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_coupon_record` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_customer`
--

DROP TABLE IF EXISTS `mall_customer`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_customer` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `openid` varchar(64) NOT NULL COMMENT '微信小程序openid',
  `unionid` varchar(64) DEFAULT NULL COMMENT '微信开放平台unionid,同一微信主体下多端(公众号/APP)打通时使用',
  `nickname` varchar(64) DEFAULT NULL,
  `avatar_url` varchar(255) DEFAULT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `gender` int DEFAULT NULL COMMENT '0-未知 1-男 2-女',
  `status` int NOT NULL DEFAULT '1' COMMENT '0-禁用 1-正常',
  `member_level_id` bigint DEFAULT NULL COMMENT '当前会员等级,为空表示默认等级(不建默认等级记录,应用层兜底展示为"普通会员")',
  `points` int NOT NULL DEFAULT '0' COMMENT '当前可用积分,必须等于该客户所有未过期批次的剩余之和;变动必须同时写 mall_points_log',
  `growth_value` int NOT NULL DEFAULT '0' COMMENT '近 N 个月滚动成长值(字典 growth_roll_months),可增可减;来源是 mall_growth_log',
  `register_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `last_login_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_openid` (`tenant_id`,`openid`),
  KEY `idx_tenant_unionid` (`tenant_id`,`unionid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商城客户表,独立于sys_user体系';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_customer`
--

LOCK TABLES `mall_customer` WRITE;
/*!40000 ALTER TABLE `mall_customer` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_customer` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_customer_address`
--

DROP TABLE IF EXISTS `mall_customer_address`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_customer_address` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `receiver_name` varchar(32) NOT NULL,
  `receiver_phone` varchar(20) NOT NULL,
  `province` varchar(32) NOT NULL,
  `city` varchar(32) NOT NULL,
  `district` varchar(32) NOT NULL,
  `detail_address` varchar(255) NOT NULL,
  `is_default` int NOT NULL DEFAULT '0' COMMENT '1-默认地址,同一客户至多一条为1,应用层保证',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_customer` (`tenant_id`,`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户收货地址';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_customer_address`
--

LOCK TABLES `mall_customer_address` WRITE;
/*!40000 ALTER TABLE `mall_customer_address` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_customer_address` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_freight_template`
--

DROP TABLE IF EXISTS `mall_freight_template`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_freight_template` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `template_name` varchar(64) NOT NULL,
  `charge_type` int NOT NULL COMMENT '1-按件数 2-按重量',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='运费模板主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_freight_template`
--

LOCK TABLES `mall_freight_template` WRITE;
/*!40000 ALTER TABLE `mall_freight_template` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_freight_template` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_freight_template_rule`
--

DROP TABLE IF EXISTS `mall_freight_template_rule`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_freight_template_rule` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `template_id` bigint NOT NULL,
  `region` varchar(255) NOT NULL DEFAULT 'ALL' COMMENT '适用区域,ALL表示不限区域,否则按省份逗号分隔存储',
  `first_unit` decimal(10,3) NOT NULL COMMENT '首件/首重',
  `first_fee` decimal(10,2) NOT NULL COMMENT '首件/首重运费',
  `additional_unit` decimal(10,3) NOT NULL COMMENT '续件/续重',
  `additional_fee` decimal(10,2) NOT NULL COMMENT '续件/续重运费',
  `free_shipping_amount` decimal(10,2) DEFAULT NULL COMMENT '满多少金额包邮,为空表示不设包邮门槛',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_template` (`tenant_id`,`template_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='运费计费规则,按区域可设不同规则';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_freight_template_rule`
--

LOCK TABLES `mall_freight_template_rule` WRITE;
/*!40000 ALTER TABLE `mall_freight_template_rule` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_freight_template_rule` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_goods`
--

DROP TABLE IF EXISTS `mall_goods`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_goods` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `category_id` bigint NOT NULL,
  `goods_name` varchar(128) NOT NULL,
  `goods_subtitle` varchar(255) DEFAULT NULL,
  `main_image` varchar(255) NOT NULL COMMENT '主图,列表页使用',
  `detail_content` text COMMENT '商品详情富文本/HTML',
  `sale_price_min` decimal(10,2) NOT NULL COMMENT '起售价,取所有SKU最低价,用于列表页展示,SKU价格变动时同步更新',
  `sale_price_max` decimal(10,2) NOT NULL COMMENT '最高价,SKU间价格有差异时列表页展示"XX起"或区间',
  `total_stock` int NOT NULL DEFAULT '0' COMMENT '所有SKU库存汇总,冗余字段,SKU库存变动时同步更新,避免列表页逐个SKU求和',
  `sale_count` int NOT NULL DEFAULT '0' COMMENT '累计销量冗余字段',
  `freight_template_id` bigint DEFAULT NULL COMMENT '关联运费模板,为空表示包邮',
  `status` int NOT NULL DEFAULT '0' COMMENT '0-下架 1-上架',
  `sort_order` int NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL COMMENT '关联sys_user.id,商家后台操作人',
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_category` (`tenant_id`,`category_id`),
  KEY `idx_tenant_status` (`tenant_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_goods`
--

LOCK TABLES `mall_goods` WRITE;
/*!40000 ALTER TABLE `mall_goods` DISABLE KEYS */;
INSERT INTO `mall_goods` VALUES (101916703608410112,101916536758996992,101916702379479040,'无线蓝牙耳机 Pro','主动降噪 · 30 小时续航','https://picsum.photos/seed/mall-1/800/800','<p>无线蓝牙耳机 Pro</p><p>主动降噪 · 30 小时续航</p><p>演示数据,用于验证页面渲染效果。</p>',399.00,446.88,43,0,NULL,1,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704237555712,101916536758996992,101916702379479040,'磁吸无线充电宝 10000mAh','自带支架 · 15W 快充','https://picsum.photos/seed/mall-2/800/800','<p>磁吸无线充电宝 10000mAh</p><p>自带支架 · 15W 快充</p><p>演示数据,用于验证页面渲染效果。</p>',159.00,159.00,25,0,NULL,1,2,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704535351296,101916536758996992,101916702530473984,'长绒棉四件套 1.8m 床','60 支贡缎 · 亲肤不起球','https://picsum.photos/seed/mall-3/800/800','<p>长绒棉四件套 1.8m 床</p><p>60 支贡缎 · 亲肤不起球</p><p>演示数据,用于验证页面渲染效果。</p>',299.00,334.88,99,0,NULL,1,3,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704896061440,101916536758996992,101916702530473984,'大豆蜡香薰蜡烛礼盒','三种香型 · 燃时 40 小时','https://picsum.photos/seed/mall-4/800/800','<p>大豆蜡香薰蜡烛礼盒</p><p>三种香型 · 燃时 40 小时</p><p>演示数据,用于验证页面渲染效果。</p>',99.00,110.88,73,0,NULL,1,4,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705244188672,101916536758996992,101916702685663232,'每日坚果混合装 30 袋','独立小包 · 无添加蔗糖','https://picsum.photos/seed/mall-5/800/800','<p>每日坚果混合装 30 袋</p><p>独立小包 · 无添加蔗糖</p><p>演示数据,用于验证页面渲染效果。</p>',79.90,89.49,83,0,NULL,1,5,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705583927296,101916536758996992,101916702685663232,'冷萃咖啡液 10 条装','0 糖 0 脂 · 冷热皆宜','https://picsum.photos/seed/mall-6/800/800','<p>冷萃咖啡液 10 条装</p><p>0 糖 0 脂 · 冷热皆宜</p><p>演示数据,用于验证页面渲染效果。</p>',59.00,66.08,93,0,NULL,1,6,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705877528576,101916536758996992,101916702845046784,'氨基酸温和洁面乳','敏感肌可用 · 不紧绷','https://picsum.photos/seed/mall-7/800/800','<p>氨基酸温和洁面乳</p><p>敏感肌可用 · 不紧绷</p><p>演示数据,用于验证页面渲染效果。</p>',69.00,69.00,50,0,NULL,1,7,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916706166935552,101916536758996992,101916702845046784,'玻尿酸保湿精华 30ml','小分子渗透 · 干皮适用','https://picsum.photos/seed/mall-8/800/800','<p>玻尿酸保湿精华 30ml</p><p>小分子渗透 · 干皮适用</p><p>演示数据,用于验证页面渲染效果。</p>',189.00,211.68,113,0,NULL,1,8,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520);
/*!40000 ALTER TABLE `mall_goods` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_goods_category`
--

DROP TABLE IF EXISTS `mall_goods_category`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_goods_category` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `parent_id` bigint NOT NULL DEFAULT '0',
  `category_name` varchar(64) NOT NULL,
  `icon` varchar(255) DEFAULT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `status` int NOT NULL DEFAULT '1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_parent` (`tenant_id`,`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品分类,支持两级(一级/二级),不做深层级树';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_goods_category`
--

LOCK TABLES `mall_goods_category` WRITE;
/*!40000 ALTER TABLE `mall_goods_category` DISABLE KEYS */;
INSERT INTO `mall_goods_category` VALUES (101916702379479040,101916536758996992,0,'数码配件',NULL,1,1,'2026-10-09 13:40:35','2026-10-09 13:40:35',101916537207787520,101916537207787520),(101916702530473984,101916536758996992,0,'家居日用',NULL,2,1,'2026-10-09 13:40:35','2026-10-09 13:40:35',101916537207787520,101916537207787520),(101916702685663232,101916536758996992,0,'零食饮料',NULL,3,1,'2026-10-09 13:40:35','2026-10-09 13:40:35',101916537207787520,101916537207787520),(101916702845046784,101916536758996992,0,'美妆个护',NULL,4,1,'2026-10-09 13:40:35','2026-10-09 13:40:35',101916537207787520,101916537207787520);
/*!40000 ALTER TABLE `mall_goods_category` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_goods_image`
--

DROP TABLE IF EXISTS `mall_goods_image`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_goods_image` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `goods_id` bigint NOT NULL,
  `image_url` varchar(255) NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_goods` (`tenant_id`,`goods_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品轮播图,与主图(mall_goods.main_image)分开维护';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_goods_image`
--

LOCK TABLES `mall_goods_image` WRITE;
/*!40000 ALTER TABLE `mall_goods_image` DISABLE KEYS */;
INSERT INTO `mall_goods_image` VALUES (101916703620993024,101916536758996992,101916703608410112,'https://picsum.photos/seed/mall-1/800/800',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916703625187328,101916536758996992,101916703608410112,'https://picsum.photos/seed/mall-1-a/800/800',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916703629381632,101916536758996992,101916703608410112,'https://picsum.photos/seed/mall-1-b/800/800',2,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704237555713,101916536758996992,101916704237555712,'https://picsum.photos/seed/mall-2/800/800',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704241750016,101916536758996992,101916704237555712,'https://picsum.photos/seed/mall-2-a/800/800',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704241750017,101916536758996992,101916704237555712,'https://picsum.photos/seed/mall-2-b/800/800',2,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704535351297,101916536758996992,101916704535351296,'https://picsum.photos/seed/mall-3/800/800',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704539545600,101916536758996992,101916704535351296,'https://picsum.photos/seed/mall-3-a/800/800',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704539545601,101916536758996992,101916704535351296,'https://picsum.photos/seed/mall-3-b/800/800',2,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704896061441,101916536758996992,101916704896061440,'https://picsum.photos/seed/mall-4/800/800',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704900255744,101916536758996992,101916704896061440,'https://picsum.photos/seed/mall-4-a/800/800',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704900255745,101916536758996992,101916704896061440,'https://picsum.photos/seed/mall-4-b/800/800',2,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705248382976,101916536758996992,101916705244188672,'https://picsum.photos/seed/mall-5/800/800',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705248382977,101916536758996992,101916705244188672,'https://picsum.photos/seed/mall-5-a/800/800',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705248382978,101916536758996992,101916705244188672,'https://picsum.photos/seed/mall-5-b/800/800',2,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705583927297,101916536758996992,101916705583927296,'https://picsum.photos/seed/mall-6/800/800',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705583927298,101916536758996992,101916705583927296,'https://picsum.photos/seed/mall-6-a/800/800',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705588121600,101916536758996992,101916705583927296,'https://picsum.photos/seed/mall-6-b/800/800',2,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705877528577,101916536758996992,101916705877528576,'https://picsum.photos/seed/mall-7/800/800',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705877528578,101916536758996992,101916705877528576,'https://picsum.photos/seed/mall-7-a/800/800',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705881722880,101916536758996992,101916705877528576,'https://picsum.photos/seed/mall-7-b/800/800',2,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916706171129856,101916536758996992,101916706166935552,'https://picsum.photos/seed/mall-8/800/800',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916706171129857,101916536758996992,101916706166935552,'https://picsum.photos/seed/mall-8-a/800/800',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916706171129858,101916536758996992,101916706166935552,'https://picsum.photos/seed/mall-8-b/800/800',2,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520);
/*!40000 ALTER TABLE `mall_goods_image` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_goods_review`
--

DROP TABLE IF EXISTS `mall_goods_review`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_goods_review` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `goods_id` bigint NOT NULL,
  `order_item_id` bigint NOT NULL COMMENT '关联到具体订单明细行,一个订单明细只能评价一次,应用层校验',
  `customer_id` bigint NOT NULL,
  `rating` int NOT NULL COMMENT '1-5星',
  `content` varchar(500) DEFAULT NULL,
  `images` text COMMENT '晒图URL列表,JSON数组',
  `is_anonymous` int NOT NULL DEFAULT '0',
  `reply_content` varchar(500) DEFAULT NULL COMMENT '商家回复',
  `reply_time` datetime DEFAULT NULL,
  `status` int NOT NULL DEFAULT '1' COMMENT '0-隐藏(违规下架) 1-展示',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_goods` (`tenant_id`,`goods_id`),
  KEY `idx_tenant_order_item` (`tenant_id`,`order_item_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品评价';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_goods_review`
--

LOCK TABLES `mall_goods_review` WRITE;
/*!40000 ALTER TABLE `mall_goods_review` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_goods_review` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_goods_spec`
--

DROP TABLE IF EXISTS `mall_goods_spec`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_goods_spec` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `goods_id` bigint NOT NULL,
  `spec_name` varchar(32) NOT NULL COMMENT '规格名,如"颜色""尺码"',
  `sort_order` int NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_goods` (`tenant_id`,`goods_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='商品规格名,一个商品可有多组规格(颜色、尺码等)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_goods_spec`
--

LOCK TABLES `mall_goods_spec` WRITE;
/*!40000 ALTER TABLE `mall_goods_spec` DISABLE KEYS */;
INSERT INTO `mall_goods_spec` VALUES (101916703646158848,101916536758996992,101916703608410112,'规格',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704543739904,101916536758996992,101916704535351296,'规格',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704904450048,101916536758996992,101916704896061440,'规格',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705252577280,101916536758996992,101916705244188672,'规格',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705588121601,101916536758996992,101916705583927296,'规格',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916706175324160,101916536758996992,101916706166935552,'规格',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520);
/*!40000 ALTER TABLE `mall_goods_spec` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_goods_spec_value`
--

DROP TABLE IF EXISTS `mall_goods_spec_value`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_goods_spec_value` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `spec_id` bigint NOT NULL,
  `spec_value` varchar(32) NOT NULL COMMENT '规格值,如"红色""XL"',
  `sort_order` int NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_spec` (`tenant_id`,`spec_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='规格值';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_goods_spec_value`
--

LOCK TABLES `mall_goods_spec_value` WRITE;
/*!40000 ALTER TABLE `mall_goods_spec_value` DISABLE KEYS */;
INSERT INTO `mall_goods_spec_value` VALUES (101916703662936064,101916536758996992,101916703646158848,'曜石黑',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916703667130368,101916536758996992,101916703646158848,'云雾白',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704547934208,101916536758996992,101916704543739904,'奶油白',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704547934209,101916536758996992,101916704543739904,'雾霾蓝',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704552128512,101916536758996992,101916704543739904,'焦糖棕',2,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704904450049,101916536758996992,101916704904450048,'雪松',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704904450050,101916536758996992,101916704904450048,'白茶',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705256771584,101916536758996992,101916705252577280,'30 袋装',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705256771585,101916536758996992,101916705252577280,'60 袋装',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705592315904,101916536758996992,101916705588121601,'经典',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705596510208,101916536758996992,101916705588121601,'果香',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916706179518464,101916536758996992,101916706175324160,'30ml',0,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916706183712768,101916536758996992,101916706175324160,'50ml',1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520);
/*!40000 ALTER TABLE `mall_goods_spec_value` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_growth_log`
--

DROP TABLE IF EXISTS `mall_growth_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_growth_log` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `change_growth` int NOT NULL COMMENT '变动数量,正数为增加负数为减少',
  `biz_type` int NOT NULL COMMENT '1-确认收货发放 3-退款扣回 4-管理端手动调整',
  `biz_id` bigint DEFAULT NULL COMMENT '关联订单ID',
  `biz_ref_id` bigint DEFAULT NULL COMMENT '关联售后单ID,退款扣回幂等用',
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_customer_time` (`tenant_id`,`customer_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='成长值流水,滚动窗口内求和即当前成长值';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_growth_log`
--

LOCK TABLES `mall_growth_log` WRITE;
/*!40000 ALTER TABLE `mall_growth_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_growth_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_member_level`
--

DROP TABLE IF EXISTS `mall_member_level`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_member_level` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `level_name` varchar(32) NOT NULL,
  `level_sort` int NOT NULL DEFAULT '0' COMMENT '等级顺序,数字越大等级越高',
  `growth_threshold` int NOT NULL COMMENT '达到该成长值自动晋升到此等级',
  `status` int NOT NULL DEFAULT '1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会员等级定义,本期只建字段,晋升/权益逻辑不在本期实现范围';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_member_level`
--

LOCK TABLES `mall_member_level` WRITE;
/*!40000 ALTER TABLE `mall_member_level` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_member_level` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_order`
--

DROP TABLE IF EXISTS `mall_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_order` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `order_no` varchar(32) NOT NULL COMMENT '对外展示单号,格式见3.6,不用雪花ID直接展示',
  `customer_id` bigint NOT NULL,
  `status` int NOT NULL COMMENT '见3.4订单状态机',
  `goods_amount` decimal(10,2) NOT NULL COMMENT '商品总金额,下单时各SKU价格快照求和',
  `freight_amount` decimal(10,2) NOT NULL DEFAULT '0.00',
  `coupon_discount_amount` decimal(10,2) NOT NULL DEFAULT '0.00',
  `promotion_discount_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '满减活动减免金额',
  `pay_amount` decimal(10,2) NOT NULL COMMENT '实付金额=goods_amount+freight_amount-两项优惠',
  `points_used` int NOT NULL DEFAULT '0' COMMENT '本单积分抵现消耗的积分数',
  `points_discount_amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '积分抵现金额,上限为商品金额的50%,运费不可抵',
  `coupon_record_id` bigint DEFAULT NULL COMMENT '使用的优惠券领取记录',
  `receiver_name` varchar(32) NOT NULL COMMENT '下单时收货信息快照,不关联地址表(地址后续可能被删改)',
  `receiver_phone` varchar(20) NOT NULL,
  `receiver_address` varchar(255) NOT NULL COMMENT '省市区+详细地址拼接快照',
  `remark` varchar(255) DEFAULT NULL COMMENT '买家留言',
  `logistics_company` varchar(64) DEFAULT NULL,
  `logistics_no` varchar(64) DEFAULT NULL,
  `ship_time` datetime DEFAULT NULL,
  `receive_time` datetime DEFAULT NULL COMMENT '确认收货时间',
  `pay_time` datetime DEFAULT NULL,
  `cancel_time` datetime DEFAULT NULL,
  `close_reason` int DEFAULT NULL COMMENT '1-超时未支付 2-买家取消 3-商家取消',
  `finish_time` datetime DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_order_no` (`tenant_id`,`order_no`),
  KEY `idx_tenant_customer` (`tenant_id`,`customer_id`),
  KEY `idx_tenant_status` (`tenant_id`,`status`),
  KEY `idx_tenant_create_time` (`tenant_id`,`create_time`) COMMENT '订单超时关闭任务按创建时间扫描待支付单'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_order`
--

LOCK TABLES `mall_order` WRITE;
/*!40000 ALTER TABLE `mall_order` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_order` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_order_item`
--

DROP TABLE IF EXISTS `mall_order_item`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_order_item` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `sku_id` bigint NOT NULL,
  `goods_id` bigint NOT NULL COMMENT '冗余,售后/评价按goods_id查询更方便',
  `goods_name` varchar(128) NOT NULL COMMENT '下单时快照,商品改名不影响历史订单展示',
  `sku_name` varchar(128) NOT NULL COMMENT '下单时快照',
  `goods_image` varchar(255) NOT NULL COMMENT '下单时快照',
  `price` decimal(10,2) NOT NULL COMMENT '下单时单价快照',
  `quantity` int NOT NULL,
  `total_amount` decimal(10,2) NOT NULL COMMENT 'price*quantity',
  `after_sale_status` int NOT NULL DEFAULT '0' COMMENT '0-无售后 1-售后处理中 2-售后已完成,避免每次查mall_after_sale判断入口是否可点',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_order` (`tenant_id`,`order_id`),
  KEY `idx_tenant_goods` (`tenant_id`,`goods_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单明细,商品/SKU信息全部落快照,不实时关联主表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_order_item`
--

LOCK TABLES `mall_order_item` WRITE;
/*!40000 ALTER TABLE `mall_order_item` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_order_item` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_order_status_log`
--

DROP TABLE IF EXISTS `mall_order_status_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_order_status_log` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `from_status` int DEFAULT NULL,
  `to_status` int NOT NULL,
  `operator_type` int NOT NULL COMMENT '1-买家 2-商家 3-系统自动',
  `operator_id` bigint DEFAULT NULL,
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_order` (`tenant_id`,`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单状态流转记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_order_status_log`
--

LOCK TABLES `mall_order_status_log` WRITE;
/*!40000 ALTER TABLE `mall_order_status_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_order_status_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_points_batch`
--

DROP TABLE IF EXISTS `mall_points_batch`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_points_batch` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `source_log_id` bigint DEFAULT NULL COMMENT '来源发放流水ID,便于追溯',
  `total_points` int NOT NULL COMMENT '本批次发放总量,不可变',
  `remain_points` int NOT NULL COMMENT '本批次剩余可用,消耗/退回/过期时增减',
  `expire_time` datetime NOT NULL COMMENT '过期时间=发放时间+有效期(字典 points_expire_months)',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_customer_expire` (`tenant_id`,`customer_id`,`expire_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='积分批次账,可用积分=Σ(remain_points>0 且未过期);消耗按 expire_time asc,id asc';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_points_batch`
--

LOCK TABLES `mall_points_batch` WRITE;
/*!40000 ALTER TABLE `mall_points_batch` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_points_batch` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_points_log`
--

DROP TABLE IF EXISTS `mall_points_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_points_log` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `customer_id` bigint NOT NULL,
  `change_points` int NOT NULL COMMENT '变动数量,正数为增加负数为减少',
  `balance_points` int NOT NULL COMMENT '变动后余额,便于对账,不用每次汇总流水计算',
  `biz_type` int NOT NULL COMMENT '1-确认收货发放 2-抵现消耗 3-退款扣回 4-管理端手动调整 5-过期清零 6-订单关闭退回',
  `biz_id` bigint DEFAULT NULL COMMENT '关联业务ID,如订单ID,管理端手动调整时为空',
  `biz_ref_id` bigint DEFAULT NULL COMMENT '关联售后单ID,仅退款扣回有值',
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_customer` (`tenant_id`,`customer_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='积分变动流水,customer.points字段的每次变化都必须对应一条这里的记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_points_log`
--

LOCK TABLES `mall_points_log` WRITE;
/*!40000 ALTER TABLE `mall_points_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_points_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_points_use`
--

DROP TABLE IF EXISTS `mall_points_use`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_points_use` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `batch_id` bigint NOT NULL,
  `points` int NOT NULL COMMENT '本订单从该批次消耗的积分数',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_order` (`tenant_id`,`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单抵现占用的积分批次明细,订单关闭时按此退回原批次';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_points_use`
--

LOCK TABLES `mall_points_use` WRITE;
/*!40000 ALTER TABLE `mall_points_use` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_points_use` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_promotion_full_reduction`
--

DROP TABLE IF EXISTS `mall_promotion_full_reduction`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_promotion_full_reduction` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `activity_name` varchar(64) NOT NULL,
  `reduction_rule` text NOT NULL COMMENT '满减阶梯规则,JSON数组,如[{"amount":100,"reduce":10},{"amount":200,"reduce":30}]',
  `scope_type` int NOT NULL DEFAULT '1' COMMENT '1-全部商品 2-指定分类 3-指定商品',
  `valid_start_time` datetime NOT NULL,
  `valid_end_time` datetime NOT NULL,
  `status` int NOT NULL DEFAULT '1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='满减活动';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_promotion_full_reduction`
--

LOCK TABLES `mall_promotion_full_reduction` WRITE;
/*!40000 ALTER TABLE `mall_promotion_full_reduction` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_promotion_full_reduction` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_promotion_full_reduction_scope`
--

DROP TABLE IF EXISTS `mall_promotion_full_reduction_scope`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_promotion_full_reduction_scope` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `activity_id` bigint NOT NULL,
  `scope_id` bigint NOT NULL COMMENT '按activity.scope_type,存category_id或goods_id',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_activity` (`tenant_id`,`activity_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='满减活动适用范围,scope_type=1(全部商品)时本表不需要记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_promotion_full_reduction_scope`
--

LOCK TABLES `mall_promotion_full_reduction_scope` WRITE;
/*!40000 ALTER TABLE `mall_promotion_full_reduction_scope` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_promotion_full_reduction_scope` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_sku`
--

DROP TABLE IF EXISTS `mall_sku`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_sku` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `goods_id` bigint NOT NULL,
  `sku_code` varchar(64) NOT NULL COMMENT '商家自定义SKU编码,同一租户下唯一',
  `sku_name` varchar(128) NOT NULL COMMENT '规格值组合拼接展示,如"红色/XL",冗余字段避免每次查关联表拼接',
  `sku_image` varchar(255) DEFAULT NULL COMMENT '该规格对应的图,为空则用商品主图',
  `price` decimal(10,2) NOT NULL,
  `cost_price` decimal(10,2) DEFAULT NULL COMMENT '成本价,用于毛利统计,不对客户端暴露',
  `stock` int NOT NULL DEFAULT '0' COMMENT '实际库存',
  `locked_stock` int NOT NULL DEFAULT '0' COMMENT '下单未支付锁定的库存,可售库存=stock-locked_stock',
  `weight` decimal(10,3) DEFAULT NULL COMMENT '重量(kg),按重量计运费时使用',
  `status` int NOT NULL DEFAULT '1' COMMENT '0-停售 1-正常',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_sku_code` (`tenant_id`,`sku_code`),
  KEY `idx_tenant_goods` (`tenant_id`,`goods_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SKU,多规格价格与库存的实际载体';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_sku`
--

LOCK TABLES `mall_sku` WRITE;
/*!40000 ALTER TABLE `mall_sku` DISABLE KEYS */;
INSERT INTO `mall_sku` VALUES (101916703671324672,101916536758996992,101916703608410112,'SKU-01-1','曜石黑','https://picsum.photos/seed/mall-1-0/400/400',399.00,239.40,20,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916703688101889,101916536758996992,101916703608410112,'SKU-01-2','云雾白','https://picsum.photos/seed/mall-1-1/400/400',446.88,239.40,23,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704241750018,101916536758996992,101916704237555712,'SKU-02-1','星空灰','https://picsum.photos/seed/mall-2-0/400/400',159.00,95.40,25,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704552128513,101916536758996992,101916704535351296,'SKU-03-1','奶油白','https://picsum.photos/seed/mall-3-0/400/400',299.00,179.40,30,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704552128515,101916536758996992,101916704535351296,'SKU-03-2','雾霾蓝','https://picsum.photos/seed/mall-3-1/400/400',334.88,179.40,33,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704556322817,101916536758996992,101916704535351296,'SKU-03-3','焦糖棕','https://picsum.photos/seed/mall-3-2/400/400',334.88,179.40,36,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704904450051,101916536758996992,101916704896061440,'SKU-04-1','雪松','https://picsum.photos/seed/mall-4-0/400/400',99.00,59.40,35,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704908644353,101916536758996992,101916704896061440,'SKU-04-2','白茶','https://picsum.photos/seed/mall-4-1/400/400',110.88,59.40,38,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705260965888,101916536758996992,101916705244188672,'SKU-05-1','30 袋装','https://picsum.photos/seed/mall-5-0/400/400',79.90,47.94,40,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705260965890,101916536758996992,101916705244188672,'SKU-05-2','60 袋装','https://picsum.photos/seed/mall-5-1/400/400',89.49,47.94,43,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705596510209,101916536758996992,101916705583927296,'SKU-06-1','经典','https://picsum.photos/seed/mall-6-0/400/400',59.00,35.40,45,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705596510211,101916536758996992,101916705583927296,'SKU-06-2','果香','https://picsum.photos/seed/mall-6-1/400/400',66.08,35.40,48,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705881722881,101916536758996992,101916705877528576,'SKU-07-1','120g','https://picsum.photos/seed/mall-7-0/400/400',69.00,41.40,50,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916706187907072,101916536758996992,101916706166935552,'SKU-08-1','30ml','https://picsum.photos/seed/mall-8-0/400/400',189.00,113.40,55,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916706187907074,101916536758996992,101916706166935552,'SKU-08-2','50ml','https://picsum.photos/seed/mall-8-1/400/400',211.68,113.40,58,0,0.500,1,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520);
/*!40000 ALTER TABLE `mall_sku` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_sku_spec_value`
--

DROP TABLE IF EXISTS `mall_sku_spec_value`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_sku_spec_value` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `sku_id` bigint NOT NULL,
  `spec_value_id` bigint NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_sku_spec_value` (`sku_id`,`spec_value_id`) COMMENT '"一个SKU每组规格只能取一个值"由唯一键保证',
  KEY `idx_tenant_sku` (`tenant_id`,`sku_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='SKU与规格值的组合关系,一个SKU对应每组规格各一个值';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_sku_spec_value`
--

LOCK TABLES `mall_sku_spec_value` WRITE;
/*!40000 ALTER TABLE `mall_sku_spec_value` DISABLE KEYS */;
INSERT INTO `mall_sku_spec_value` VALUES (101916703688101888,101916536758996992,101916703671324672,101916703662936064,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916703696490496,101916536758996992,101916703688101889,101916703667130368,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704552128514,101916536758996992,101916704552128513,101916704547934208,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704556322816,101916536758996992,101916704552128515,101916704547934209,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704560517120,101916536758996992,101916704556322817,101916704552128512,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704908644352,101916536758996992,101916704904450051,101916704904450049,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916704912838656,101916536758996992,101916704908644353,101916704904450050,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705260965889,101916536758996992,101916705260965888,101916705256771584,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705260965891,101916536758996992,101916705260965890,101916705256771585,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705596510210,101916536758996992,101916705596510209,101916705592315904,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916705600704512,101916536758996992,101916705596510211,101916705596510208,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916706187907073,101916536758996992,101916706187907072,101916706179518464,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520),(101916706187907075,101916536758996992,101916706187907074,101916706183712768,'2026-10-09 13:40:36','2026-10-09 13:40:36',101916537207787520,101916537207787520);
/*!40000 ALTER TABLE `mall_sku_spec_value` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_stock_log`
--

DROP TABLE IF EXISTS `mall_stock_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_stock_log` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `sku_id` bigint NOT NULL,
  `change_type` int NOT NULL COMMENT '1-下单锁定 2-超时释放 3-支付扣减实际库存 4-退款回库 5-管理端手动调整',
  `change_stock` int NOT NULL COMMENT '变动数量,正数增加负数减少',
  `change_locked` int NOT NULL DEFAULT '0' COMMENT 'locked_stock的变动量',
  `biz_id` bigint DEFAULT NULL COMMENT '关联订单ID,手动调整时为空',
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_sku` (`tenant_id`,`sku_id`),
  KEY `idx_tenant_biz` (`tenant_id`,`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存变动流水,stock/locked_stock的每次变化必须对应一条记录';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_stock_log`
--

LOCK TABLES `mall_stock_log` WRITE;
/*!40000 ALTER TABLE `mall_stock_log` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_stock_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_wx_payment`
--

DROP TABLE IF EXISTS `mall_wx_payment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_wx_payment` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `wx_transaction_id` varchar(64) DEFAULT NULL COMMENT '微信支付订单号,回调后写入',
  `out_trade_no` varchar(32) NOT NULL COMMENT '商户订单号,等于mall_order.order_no',
  `pay_amount` decimal(10,2) NOT NULL,
  `pay_status` int NOT NULL DEFAULT '0' COMMENT '0-待支付 1-支付成功 2-支付失败/已关闭',
  `prepay_id` varchar(64) DEFAULT NULL COMMENT '统一下单接口返回的预支付ID',
  `callback_time` datetime DEFAULT NULL,
  `raw_callback` text COMMENT '微信回调原始报文,便于对账排查,不做结构化解析存储',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_out_trade_no` (`tenant_id`,`out_trade_no`),
  UNIQUE KEY `uk_wx_transaction` (`wx_transaction_id`) COMMENT '防止同一笔支付回调重复处理造成重复入账,见3.8幂等设计',
  KEY `idx_tenant_order` (`tenant_id`,`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信支付流水';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_wx_payment`
--

LOCK TABLES `mall_wx_payment` WRITE;
/*!40000 ALTER TABLE `mall_wx_payment` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_wx_payment` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `mall_wx_refund`
--

DROP TABLE IF EXISTS `mall_wx_refund`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `mall_wx_refund` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `order_id` bigint NOT NULL,
  `after_sale_id` bigint NOT NULL,
  `wx_refund_id` varchar(64) DEFAULT NULL COMMENT '微信退款单号,回调后写入',
  `out_refund_no` varchar(32) NOT NULL COMMENT '商户退款单号',
  `refund_amount` decimal(10,2) NOT NULL,
  `refund_status` int NOT NULL DEFAULT '0' COMMENT '0-申请中 1-退款成功 2-退款失败',
  `callback_time` datetime DEFAULT NULL,
  `raw_callback` text,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_out_refund_no` (`tenant_id`,`out_refund_no`),
  UNIQUE KEY `uk_wx_refund` (`wx_refund_id`),
  KEY `idx_tenant_after_sale` (`tenant_id`,`after_sale_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信退款流水,与支付流水分表,字段不同不要合并';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `mall_wx_refund`
--

LOCK TABLES `mall_wx_refund` WRITE;
/*!40000 ALTER TABLE `mall_wx_refund` DISABLE KEYS */;
/*!40000 ALTER TABLE `mall_wx_refund` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_dept`
--

DROP TABLE IF EXISTS `sys_dept`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dept` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `parent_id` bigint NOT NULL DEFAULT '0',
  `ancestors` varchar(255) NOT NULL DEFAULT '' COMMENT '祖级列表,逗号分隔,便于"本部门及以下"查询',
  `dept_name` varchar(64) NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `status` int NOT NULL DEFAULT '1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_dept_name` (`tenant_id`,`dept_name`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='部门表。删除规则:有子部门或有关联用户时拒绝删除,见架构文档5.6';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_dept`
--

LOCK TABLES `sys_dept` WRITE;
/*!40000 ALTER TABLE `sys_dept` DISABLE KEYS */;
INSERT INTO `sys_dept` VALUES (1,1,0,'','平台总部',1,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(101916536847077376,101916536758996992,0,'','演示商户总部',1,1,'2026-10-09 13:39:56','2026-10-09 13:39:56',1,1);
/*!40000 ALTER TABLE `sys_dept` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_dict_data`
--

DROP TABLE IF EXISTS `sys_dict_data`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dict_data` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `dict_type` varchar(64) NOT NULL,
  `dict_label` varchar(64) NOT NULL,
  `dict_value` varchar(64) NOT NULL,
  `sort_order` int NOT NULL DEFAULT '0',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_type_value` (`dict_type`,`dict_value`),
  KEY `idx_type` (`dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典数据表,平台级数据(BaseAuditEntity)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_dict_data`
--

LOCK TABLES `sys_dict_data` WRITE;
/*!40000 ALTER TABLE `sys_dict_data` DISABLE KEYS */;
INSERT INTO `sys_dict_data` VALUES (1,'sys_common_status','正常','1',1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(2,'sys_common_status','停用','0',2,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(3,'sys_menu_type','目录','1',1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(4,'sys_menu_type','页面','2',2,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(5,'sys_menu_type','按钮','3',3,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(6,'tenant_expire_notice_days','提前天数','7',1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(101,'order_pay_timeout_minutes','15分钟','15',1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(102,'order_auto_receive_days','15天','15',1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(103,'after_sale_timeout','商家处理(小时)','72',1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(104,'after_sale_timeout','买家退货(天)','7',2,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(105,'after_sale_timeout','商家收货(天)','10',3,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(106,'points_expire_months','月数','12',1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(107,'growth_roll_months','月数','12',1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(108,'stock_warn_threshold','可售库存不高于该值即预警','5',1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL);
/*!40000 ALTER TABLE `sys_dict_data` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_dict_type`
--

DROP TABLE IF EXISTS `sys_dict_type`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_dict_type` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `dict_type` varchar(64) NOT NULL,
  `dict_name` varchar(64) NOT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `dict_type` (`dict_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='字典类型表,平台级数据(BaseAuditEntity)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_dict_type`
--

LOCK TABLES `sys_dict_type` WRITE;
/*!40000 ALTER TABLE `sys_dict_type` DISABLE KEYS */;
INSERT INTO `sys_dict_type` VALUES (1,'sys_common_status','通用状态','2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(2,'sys_menu_type','菜单类型','2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(3,'tenant_expire_notice_days','租户到期提醒天数','2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(101,'order_pay_timeout_minutes','订单支付超时分钟数','2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(102,'order_auto_receive_days','订单自动确认收货天数','2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(103,'after_sale_timeout','售后各环节超时小时数','2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(104,'points_expire_months','积分批次有效期(月)','2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(105,'growth_roll_months','成长值滚动窗口(月)','2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(106,'stock_warn_threshold','库存预警阈值(件)','2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL);
/*!40000 ALTER TABLE `sys_dict_type` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_menu`
--

DROP TABLE IF EXISTS `sys_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_menu` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `parent_id` bigint NOT NULL DEFAULT '0',
  `menu_name` varchar(64) NOT NULL,
  `menu_type` int NOT NULL COMMENT '1-目录 2-页面 3-按钮',
  `route_path` varchar(128) DEFAULT NULL COMMENT 'menu_type=2 时使用',
  `perm_code` varchar(128) DEFAULT NULL COMMENT 'menu_type=3 时使用,如 order:delete。全局唯一,否则 @SaCheckPermission 语义失效',
  `icon` varchar(64) DEFAULT NULL COMMENT '图标名,取值必须是前端 SideMenu 的 icons 表里登记过的名字(否则该菜单没有图标)',
  `is_platform` int NOT NULL DEFAULT '0' COMMENT '1-平台专用菜单(套餐管理/租户管理等),不允许进入任何套餐,见架构文档4.10',
  `sort_order` int NOT NULL DEFAULT '0',
  `status` int NOT NULL DEFAULT '1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_perm_code` (`perm_code`),
  KEY `idx_parent` (`parent_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='菜单/权限点表,全平台统一,不分租户。perm_code 可空(目录与页面没有),唯一索引允许多个 NULL';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_menu`
--

LOCK TABLES `sys_menu` WRITE;
/*!40000 ALTER TABLE `sys_menu` DISABLE KEYS */;
INSERT INTO `sys_menu` VALUES (1,0,'系统管理',1,'/system',NULL,'settings',0,1,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(2,1,'用户管理',2,'user',NULL,'people',0,1,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(3,1,'角色管理',2,'role',NULL,'shield',0,2,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(5,0,'平台管理',1,'/platform',NULL,'crown',1,9,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(6,5,'租户管理',2,'tenant',NULL,'shop',1,1,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(7,5,'套餐管理',2,'package',NULL,'gift',1,2,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(8,5,'菜单管理',2,'menu',NULL,'list',1,3,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(9,5,'字典管理',2,'dict',NULL,'book',1,4,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(10,5,'微信支付配置',2,'wx-pay',NULL,'wallet',1,5,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(11,2,'用户列表',3,NULL,'system:user:list',NULL,0,1,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(12,2,'用户详情',3,NULL,'system:user:query',NULL,0,2,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(13,2,'用户新增',3,NULL,'system:user:create',NULL,0,3,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(14,2,'用户修改',3,NULL,'system:user:update',NULL,0,4,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(15,2,'用户删除',3,NULL,'system:user:delete',NULL,0,5,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(16,2,'用户启停',3,NULL,'system:user:status',NULL,0,6,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(17,2,'重置密码',3,NULL,'system:user:reset-password',NULL,0,7,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(18,2,'解除锁定',3,NULL,'system:user:unlock',NULL,0,8,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(19,5,'操作日志',2,'oper-log',NULL,'list',1,6,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(20,5,'定时任务',2,'task-log',NULL,'time',1,7,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(21,3,'角色列表',3,NULL,'system:role:list',NULL,0,1,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(22,3,'角色新增',3,NULL,'system:role:create',NULL,0,2,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(23,3,'角色修改',3,NULL,'system:role:update',NULL,0,3,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(24,3,'角色删除',3,NULL,'system:role:delete',NULL,0,4,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(25,3,'角色启停',3,NULL,'system:role:status',NULL,0,5,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(26,3,'角色授权',3,NULL,'system:role:grant',NULL,0,6,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(31,2,'部门列表',3,NULL,'system:dept:list',NULL,0,9,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(32,2,'部门新增',3,NULL,'system:dept:create',NULL,0,10,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(33,2,'部门修改',3,NULL,'system:dept:update',NULL,0,11,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(34,2,'部门删除',3,NULL,'system:dept:delete',NULL,0,12,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(41,6,'租户列表',3,NULL,'system:tenant:list',NULL,1,1,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(42,6,'租户新增',3,NULL,'system:tenant:create',NULL,1,2,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(43,6,'租户换套餐',3,NULL,'system:tenant:package',NULL,1,3,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(44,6,'租户启停',3,NULL,'system:tenant:status',NULL,1,4,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(45,6,'租户改有效期',3,NULL,'system:tenant:expire',NULL,1,5,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(46,6,'租户注销',3,NULL,'system:tenant:close',NULL,1,6,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(51,7,'套餐列表',3,NULL,'system:package:list',NULL,1,1,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(52,7,'套餐新增',3,NULL,'system:package:create',NULL,1,2,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(53,7,'套餐修改',3,NULL,'system:package:update',NULL,1,3,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(61,8,'菜单列表',3,NULL,'system:menu:list',NULL,1,1,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(62,8,'菜单新增',3,NULL,'system:menu:create',NULL,1,2,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(63,8,'菜单修改',3,NULL,'system:menu:update',NULL,1,3,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(64,8,'菜单删除',3,NULL,'system:menu:delete',NULL,1,4,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(91,9,'字典列表',3,NULL,'system:dict:list',NULL,1,1,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(92,9,'字典新增',3,NULL,'system:dict:create',NULL,1,2,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(93,9,'字典修改',3,NULL,'system:dict:update',NULL,1,3,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(94,9,'字典删除',3,NULL,'system:dict:delete',NULL,1,4,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(101,10,'配置查询',3,NULL,'system:wxpay:list',NULL,1,1,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(102,10,'配置修改',3,NULL,'system:wxpay:update',NULL,1,2,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(191,19,'日志查询',3,NULL,'system:operlog:list',NULL,1,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(192,20,'任务日志',3,NULL,'system:tasklog:list',NULL,1,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(200,0,'商城管理',1,'/mall',NULL,'shopping',0,10,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(201,200,'商品管理',2,'goods',NULL,'shop',0,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(203,200,'订单管理',2,'order',NULL,'profile',0,3,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(204,200,'售后管理',2,'after-sale',NULL,'tool',0,4,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(205,200,'优惠券',2,'coupon',NULL,'gift',0,5,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(206,200,'满减活动',2,'promotion',NULL,'thunderbolt',0,6,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(207,200,'运费模板',2,'freight',NULL,'car',0,7,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(208,200,'会员等级',2,'member-level',NULL,'crown',0,8,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(209,200,'商品评价',2,'review',NULL,'star',0,9,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(210,200,'客户管理',2,'customer',NULL,'people',0,10,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(211,200,'库存预警',2,'stock-warn',NULL,'alert',0,2,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(212,200,'销售统计',2,'sales-stat',NULL,'chart',0,11,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2011,201,'商品列表',3,NULL,'mall:goods:list',NULL,0,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2012,201,'商品新增',3,NULL,'mall:goods:create',NULL,0,2,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2013,201,'商品修改',3,NULL,'mall:goods:update',NULL,0,3,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2014,201,'商品删除',3,NULL,'mall:goods:delete',NULL,0,4,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2015,201,'商品上下架',3,NULL,'mall:goods:status',NULL,0,5,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2021,201,'分类列表',3,NULL,'mall:category:list',NULL,0,6,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2022,201,'分类新增',3,NULL,'mall:category:create',NULL,0,7,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2023,201,'分类修改',3,NULL,'mall:category:update',NULL,0,8,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2024,201,'分类删除',3,NULL,'mall:category:delete',NULL,0,9,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2031,203,'订单列表',3,NULL,'mall:order:list',NULL,0,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2032,203,'订单发货',3,NULL,'mall:order:ship',NULL,0,2,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2033,203,'订单取消',3,NULL,'mall:order:cancel',NULL,0,3,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2041,204,'售后列表',3,NULL,'mall:after-sale:list',NULL,0,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2042,204,'售后处理',3,NULL,'mall:after-sale:handle',NULL,0,2,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2051,205,'优惠券列表',3,NULL,'mall:coupon:list',NULL,0,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2052,205,'优惠券新增',3,NULL,'mall:coupon:create',NULL,0,2,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2053,205,'优惠券修改',3,NULL,'mall:coupon:update',NULL,0,3,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2061,206,'活动列表',3,NULL,'mall:promotion:list',NULL,0,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2062,206,'活动新增',3,NULL,'mall:promotion:create',NULL,0,2,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2063,206,'活动修改',3,NULL,'mall:promotion:update',NULL,0,3,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2071,207,'模板列表',3,NULL,'mall:freight:list',NULL,0,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2072,207,'模板新增',3,NULL,'mall:freight:create',NULL,0,2,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2073,207,'模板修改',3,NULL,'mall:freight:update',NULL,0,3,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2074,207,'模板删除',3,NULL,'mall:freight:delete',NULL,0,4,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2081,208,'等级列表',3,NULL,'mall:member-level:list',NULL,0,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2082,208,'等级新增',3,NULL,'mall:member-level:create',NULL,0,2,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2083,208,'等级修改',3,NULL,'mall:member-level:update',NULL,0,3,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2091,209,'评价列表',3,NULL,'mall:review:list',NULL,0,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2092,209,'评价回复',3,NULL,'mall:review:reply',NULL,0,2,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2093,209,'评价隐藏',3,NULL,'mall:review:status',NULL,0,3,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2101,210,'客户列表',3,NULL,'mall:customer:list',NULL,0,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2102,210,'客户详情',3,NULL,'mall:customer:detail',NULL,0,2,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2103,210,'积分调整',3,NULL,'mall:customer:adjust',NULL,0,3,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2111,211,'预警列表',3,NULL,'mall:stock:list',NULL,0,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL),(2121,212,'统计查询',3,NULL,'mall:stat:list',NULL,0,1,1,'2026-10-09 13:38:31','2026-10-09 13:38:31',NULL,NULL);
/*!40000 ALTER TABLE `sys_menu` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_oper_log`
--

DROP TABLE IF EXISTS `sys_oper_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_oper_log` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint DEFAULT NULL,
  `user_id` bigint DEFAULT NULL,
  `module` varchar(64) NOT NULL,
  `perm_code` varchar(128) DEFAULT NULL,
  `method` varchar(255) NOT NULL,
  `request_params` text COMMENT '写入前必须按字段黑名单脱敏并截断(架构文档7.2),绝不允许落明文密码',
  `status` int NOT NULL COMMENT '0-失败 1-成功',
  `error_msg` text COMMENT '截断到4000字符',
  `ip` varchar(64) DEFAULT NULL,
  `trace_id` varchar(64) DEFAULT NULL COMMENT '与 MDC/响应头 X-Trace-Id 对应,见架构文档7.2',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_time` (`tenant_id`,`create_time`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='操作日志表,异步落库,不阻塞主流程';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_oper_log`
--

LOCK TABLES `sys_oper_log` WRITE;
/*!40000 ALTER TABLE `sys_oper_log` DISABLE KEYS */;
INSERT INTO `sys_oper_log` VALUES (101916534372438016,1,1,'认证','auth:login','AuthController.login(..)','LoginRequest[tenantCode=platform, username=admin, password=***, deviceId=seed, captchaId=null, captchaCode=null]',1,NULL,'127.0.0.1','71eeaced4102473faddb0f3e0e68e9ad','2026-10-09 13:39:55','2026-10-09 13:39:55',NULL,NULL),(101916535999827968,1,1,'认证','auth:password','AuthController.changePassword(..)','ChangePasswordRequest[oldPassword=***, newPassword=***]',1,NULL,'127.0.0.1','d2d47651943a48988f96647255130ec2','2026-10-09 13:39:56','2026-10-09 13:39:56',NULL,NULL),(101916536545087488,1,1,'认证','auth:login','AuthController.login(..)','LoginRequest[tenantCode=platform, username=admin, password=***, deviceId=seed, captchaId=null, captchaCode=null]',1,NULL,'127.0.0.1','9b9bcabbf9db46abb1d1649b66ff1029','2026-10-09 13:39:56','2026-10-09 13:39:56',NULL,NULL),(101916537740464128,1,1,'租户管理','system:tenant:create','SysTenantController.create(..)','TenantCreateRequest[tenantCode=demo, tenantName=演示商户, packageId=1, expireTime=null, adminUsername=shopadmin, adminNickname=演示商户管理员, adminPassword=Demo@123456]',1,NULL,'127.0.0.1','5e8f83197f0a444dbae13064af5a3227','2026-10-09 13:39:56','2026-10-09 13:39:56',NULL,NULL),(101916701163130880,1,1,'认证','auth:login','AuthController.login(..)','LoginRequest[tenantCode=platform, username=admin, password=***, deviceId=seed, captchaId=null, captchaCode=null]',1,NULL,'127.0.0.1','1d7aed6e25d94ab488d709bded758b30','2026-10-09 13:40:35','2026-10-09 13:40:35',NULL,NULL),(101916701305737216,1,1,'租户管理','system:tenant:create','SysTenantController.create(..)','TenantCreateRequest[tenantCode=demo, tenantName=演示商户, packageId=1, expireTime=null, adminUsername=shopadmin, adminNickname=演示商户管理员, adminPassword=Demo@123456]',0,'BusinessException: 租户编码已存在','127.0.0.1','49f431c1ab804b9fa5e5dfe53db86334','2026-10-09 13:40:35','2026-10-09 13:40:35',NULL,NULL),(101916702085877760,101916536758996992,101916537207787520,'认证','auth:login','AuthController.login(..)','LoginRequest[tenantCode=demo, username=shopadmin, password=***, deviceId=seed, captchaId=null, captchaCode=null]',1,NULL,'127.0.0.1','26c01069e70344dcbb935e67187bc1d6','2026-10-09 13:40:35','2026-10-09 13:40:35',NULL,NULL),(101916702413033472,101916536758996992,101916537207787520,'商品分类','mall:category:create','MallCategoryController.create(..)','CategorySaveRequest[parentId=0, categoryName=数码配件, icon=null, sortOrder=1, status=1]',1,NULL,'127.0.0.1','82ad3e31b5b2418bbaff2d2b39771feb','2026-10-09 13:40:35','2026-10-09 13:40:35',NULL,NULL),(101916702564028416,101916536758996992,101916537207787520,'商品分类','mall:category:create','MallCategoryController.create(..)','CategorySaveRequest[parentId=0, categoryName=家居日用, icon=null, sortOrder=2, status=1]',1,NULL,'127.0.0.1','a86d070284434b1088b08bb3341ec0be','2026-10-09 13:40:35','2026-10-09 13:40:35',NULL,NULL),(101916702715023360,101916536758996992,101916537207787520,'商品分类','mall:category:create','MallCategoryController.create(..)','CategorySaveRequest[parentId=0, categoryName=零食饮料, icon=null, sortOrder=3, status=1]',1,NULL,'127.0.0.1','31f6f705946a4c539177a7089d3b8e99','2026-10-09 13:40:35','2026-10-09 13:40:35',NULL,NULL),(101916702874406912,101916536758996992,101916537207787520,'商品分类','mall:category:create','MallCategoryController.create(..)','CategorySaveRequest[parentId=0, categoryName=美妆个护, icon=null, sortOrder=4, status=1]',1,NULL,'127.0.0.1','fd00ce4b4e364962ab8c9299c8d37b6c','2026-10-09 13:40:35','2026-10-09 13:40:35',NULL,NULL),(101916703922982912,101916536758996992,101916537207787520,'商品管理','mall:goods:create','MallGoodsController.create(..)','GoodsSaveRequest[categoryId=101916702379479040, goodsName=无线蓝牙耳机 Pro, goodsSubtitle=主动降噪 · 30 小时续航, mainImage=https://picsum.photos/seed/mall-1/800/800, detailContent=<p>无线蓝牙耳机 Pro</p><p>主动降噪 · 30 小时续航</p><p>演示数据,用于验证页面渲染效果。</p>, freightTemplateId=null, sortOrder=1, status=1, images=[https://picsum.photos/seed/mall-1/800/800, https://picsum.photos/seed/mall-1-a/800/800, https://picsum.photos/seed/mall-1-b/800/800], specs=[SpecSaveRequest[specName=规格, values=[曜石黑, 云雾白]]], skus=[SkuSaveRequest[id=null, skuCode=SKU-01-1, skuName=曜石黑, skuImage=https://picsum.photos/seed/mall-1-0/400/400, price=399, costPrice=239.4, stock=20, weight=0.5, status=1, specValues=[曜石黑]], SkuSaveRequest[id=null, skuCode=SKU-01-2, skuName=云雾白, skuImage=https://picsum.photos/seed/mall-1-1/400/400, price=446.88, costPrice=239.4, stock=23, weight=0.5, status=1, specValues=[云雾白]]]]',1,NULL,'127.0.0.1','34c324be58c44f10bfec3e8113cb64d8','2026-10-09 13:40:36','2026-10-09 13:40:36',NULL,NULL),(101916704350801920,101916536758996992,101916537207787520,'商品管理','mall:goods:create','MallGoodsController.create(..)','GoodsSaveRequest[categoryId=101916702379479040, goodsName=磁吸无线充电宝 10000mAh, goodsSubtitle=自带支架 · 15W 快充, mainImage=https://picsum.photos/seed/mall-2/800/800, detailContent=<p>磁吸无线充电宝 10000mAh</p><p>自带支架 · 15W 快充</p><p>演示数据,用于验证页面渲染效果。</p>, freightTemplateId=null, sortOrder=2, status=1, images=[https://picsum.photos/seed/mall-2/800/800, https://picsum.photos/seed/mall-2-a/800/800, https://picsum.photos/seed/mall-2-b/800/800], specs=[], skus=[SkuSaveRequest[id=null, skuCode=SKU-02-1, skuName=星空灰, skuImage=https://picsum.photos/seed/mall-2-0/400/400, price=159, costPrice=95.4, stock=25, weight=0.5, status=1, specValues=[]]]]',1,NULL,'127.0.0.1','007acb1ac6f54e43b07998f68057e51b','2026-10-09 13:40:36','2026-10-09 13:40:36',NULL,NULL),(101916704745066496,101916536758996992,101916537207787520,'商品管理','mall:goods:create','MallGoodsController.create(..)','GoodsSaveRequest[categoryId=101916702530473984, goodsName=长绒棉四件套 1.8m 床, goodsSubtitle=60 支贡缎 · 亲肤不起球, mainImage=https://picsum.photos/seed/mall-3/800/800, detailContent=<p>长绒棉四件套 1.8m 床</p><p>60 支贡缎 · 亲肤不起球</p><p>演示数据,用于验证页面渲染效果。</p>, freightTemplateId=null, sortOrder=3, status=1, images=[https://picsum.photos/seed/mall-3/800/800, https://picsum.photos/seed/mall-3-a/800/800, https://picsum.photos/seed/mall-3-b/800/800], specs=[SpecSaveRequest[specName=规格, values=[奶油白, 雾霾蓝, 焦糖棕]]], skus=[SkuSaveRequest[id=null, skuCode=SKU-03-1, skuName=奶油白, skuImage=https://picsum.photos/seed/mall-3-0/400/400, price=299, costPrice=179.4, stock=30, weight=0.5, status=1, specValues=[奶油白]], SkuSaveRequest[id=null, skuCode=SKU-03-2, skuName=雾霾蓝, skuImage=https://picsum.photos/seed/mall-3-1/400/400, price=334.88, costPrice=179.4, stock=33, weight=0.5, status=1, specValues=[雾霾蓝]], SkuSaveRequest[id=null, skuCode=SKU-03-3, skuName=焦糖棕, skuImage=https://picsum.photos/seed/mall-3-2/400/400, price=334.88, costPrice=179.4, stock=36, weight=0.5, status=1, specValues=[焦糖棕]]]]',1,NULL,'127.0.0.1','8f327b28ff284ffaadd0c7f252e9a24d','2026-10-09 13:40:36','2026-10-09 13:40:36',NULL,NULL),(101916705068027904,101916536758996992,101916537207787520,'商品管理','mall:goods:create','MallGoodsController.create(..)','GoodsSaveRequest[categoryId=101916702530473984, goodsName=大豆蜡香薰蜡烛礼盒, goodsSubtitle=三种香型 · 燃时 40 小时, mainImage=https://picsum.photos/seed/mall-4/800/800, detailContent=<p>大豆蜡香薰蜡烛礼盒</p><p>三种香型 · 燃时 40 小时</p><p>演示数据,用于验证页面渲染效果。</p>, freightTemplateId=null, sortOrder=4, status=1, images=[https://picsum.photos/seed/mall-4/800/800, https://picsum.photos/seed/mall-4-a/800/800, https://picsum.photos/seed/mall-4-b/800/800], specs=[SpecSaveRequest[specName=规格, values=[雪松, 白茶]]], skus=[SkuSaveRequest[id=null, skuCode=SKU-04-1, skuName=雪松, skuImage=https://picsum.photos/seed/mall-4-0/400/400, price=99, costPrice=59.4, stock=35, weight=0.5, status=1, specValues=[雪松]], SkuSaveRequest[id=null, skuCode=SKU-04-2, skuName=白茶, skuImage=https://picsum.photos/seed/mall-4-1/400/400, price=110.88, costPrice=59.4, stock=38, weight=0.5, status=1, specValues=[白茶]]]]',1,NULL,'127.0.0.1','b8383f85c6aa42f9a2631a2ac970799e','2026-10-09 13:40:36','2026-10-09 13:40:36',NULL,NULL),(101916705416155136,101916536758996992,101916537207787520,'商品管理','mall:goods:create','MallGoodsController.create(..)','GoodsSaveRequest[categoryId=101916702685663232, goodsName=每日坚果混合装 30 袋, goodsSubtitle=独立小包 · 无添加蔗糖, mainImage=https://picsum.photos/seed/mall-5/800/800, detailContent=<p>每日坚果混合装 30 袋</p><p>独立小包 · 无添加蔗糖</p><p>演示数据,用于验证页面渲染效果。</p>, freightTemplateId=null, sortOrder=5, status=1, images=[https://picsum.photos/seed/mall-5/800/800, https://picsum.photos/seed/mall-5-a/800/800, https://picsum.photos/seed/mall-5-b/800/800], specs=[SpecSaveRequest[specName=规格, values=[30 袋装, 60 袋装]]], skus=[SkuSaveRequest[id=null, skuCode=SKU-05-1, skuName=30 袋装, skuImage=https://picsum.photos/seed/mall-5-0/400/400, price=79.9, costPrice=47.94, stock=40, weight=0.5, status=1, specValues=[30 袋装]], SkuSaveRequest[id=null, skuCode=SKU-05-2, skuName=60 袋装, skuImage=https://picsum.photos/seed/mall-5-1/400/400, price=89.49, costPrice=47.94, stock=43, weight=0.5, status=1, specValues=[60 袋装]]]]',1,NULL,'127.0.0.1','bf2120addc7344cd915d6cbd1e3272c1','2026-10-09 13:40:36','2026-10-09 13:40:36',NULL,NULL),(101916705739116544,101916536758996992,101916537207787520,'商品管理','mall:goods:create','MallGoodsController.create(..)','GoodsSaveRequest[categoryId=101916702685663232, goodsName=冷萃咖啡液 10 条装, goodsSubtitle=0 糖 0 脂 · 冷热皆宜, mainImage=https://picsum.photos/seed/mall-6/800/800, detailContent=<p>冷萃咖啡液 10 条装</p><p>0 糖 0 脂 · 冷热皆宜</p><p>演示数据,用于验证页面渲染效果。</p>, freightTemplateId=null, sortOrder=6, status=1, images=[https://picsum.photos/seed/mall-6/800/800, https://picsum.photos/seed/mall-6-a/800/800, https://picsum.photos/seed/mall-6-b/800/800], specs=[SpecSaveRequest[specName=规格, values=[经典, 果香]]], skus=[SkuSaveRequest[id=null, skuCode=SKU-06-1, skuName=经典, skuImage=https://picsum.photos/seed/mall-6-0/400/400, price=59, costPrice=35.4, stock=45, weight=0.5, status=1, specValues=[经典]], SkuSaveRequest[id=null, skuCode=SKU-06-2, skuName=果香, skuImage=https://picsum.photos/seed/mall-6-1/400/400, price=66.08, costPrice=35.4, stock=48, weight=0.5, status=1, specValues=[果香]]]]',1,NULL,'127.0.0.1','57ffb80ac08842529710cb509bd7d075','2026-10-09 13:40:36','2026-10-09 13:40:36',NULL,NULL),(101916705994969088,101916536758996992,101916537207787520,'商品管理','mall:goods:create','MallGoodsController.create(..)','GoodsSaveRequest[categoryId=101916702845046784, goodsName=氨基酸温和洁面乳, goodsSubtitle=敏感肌可用 · 不紧绷, mainImage=https://picsum.photos/seed/mall-7/800/800, detailContent=<p>氨基酸温和洁面乳</p><p>敏感肌可用 · 不紧绷</p><p>演示数据,用于验证页面渲染效果。</p>, freightTemplateId=null, sortOrder=7, status=1, images=[https://picsum.photos/seed/mall-7/800/800, https://picsum.photos/seed/mall-7-a/800/800, https://picsum.photos/seed/mall-7-b/800/800], specs=[], skus=[SkuSaveRequest[id=null, skuCode=SKU-07-1, skuName=120g, skuImage=https://picsum.photos/seed/mall-7-0/400/400, price=69, costPrice=41.4, stock=50, weight=0.5, status=1, specValues=[]]]]',1,NULL,'127.0.0.1','9506aba35d354c848864b96a59d13a6d','2026-10-09 13:40:36','2026-10-09 13:40:36',NULL,NULL),(101916706330513408,101916536758996992,101916537207787520,'商品管理','mall:goods:create','MallGoodsController.create(..)','GoodsSaveRequest[categoryId=101916702845046784, goodsName=玻尿酸保湿精华 30ml, goodsSubtitle=小分子渗透 · 干皮适用, mainImage=https://picsum.photos/seed/mall-8/800/800, detailContent=<p>玻尿酸保湿精华 30ml</p><p>小分子渗透 · 干皮适用</p><p>演示数据,用于验证页面渲染效果。</p>, freightTemplateId=null, sortOrder=8, status=1, images=[https://picsum.photos/seed/mall-8/800/800, https://picsum.photos/seed/mall-8-a/800/800, https://picsum.photos/seed/mall-8-b/800/800], specs=[SpecSaveRequest[specName=规格, values=[30ml, 50ml]]], skus=[SkuSaveRequest[id=null, skuCode=SKU-08-1, skuName=30ml, skuImage=https://picsum.photos/seed/mall-8-0/400/400, price=189, costPrice=113.4, stock=55, weight=0.5, status=1, specValues=[30ml]], SkuSaveRequest[id=null, skuCode=SKU-08-2, skuName=50ml, skuImage=https://picsum.photos/seed/mall-8-1/400/400, price=211.68, costPrice=113.4, stock=58, weight=0.5, status=1, specValues=[50ml]]]]',1,NULL,'127.0.0.1','31526f976fc0405bb6360d4c5e656752','2026-10-09 13:40:36','2026-10-09 13:40:36',NULL,NULL),(101918091163865088,NULL,NULL,'认证','auth:login','AuthController.login(..)','LoginRequest[tenantCode=qingning, username=admin, password=***, deviceId=null, captchaId=f4c0bae27434451e92c992c799fe77c7, captchaCode=d4pk]',0,'BusinessException: 用户名或密码错误','127.0.0.1','339cd76da2244dc38278fe58093db418','2026-10-09 13:46:06','2026-10-09 13:46:06',NULL,NULL),(101918091235168256,NULL,NULL,'认证','auth:login','AuthController.login(..)','LoginRequest[tenantCode=qingning, username=admin, password=***, deviceId=null, captchaId=f4c0bae27434451e92c992c799fe77c7, captchaCode=d4pk]',0,'BusinessException: 用户名或密码错误','127.0.0.1','d0182d34aa03483fbd78fc745ab60391','2026-10-09 13:46:06','2026-10-09 13:46:06',NULL,NULL),(101918129793404928,NULL,NULL,'认证','auth:login','AuthController.login(..)','LoginRequest[tenantCode=qingning, username=admin, password=***, deviceId=null, captchaId=825079aa0b354b33bb6ac212b400cc89, captchaCode=tttj]',0,'BusinessException: 用户名或密码错误','127.0.0.1','cd1304300584410da0eec8f7240d36bb','2026-10-09 13:46:16','2026-10-09 13:46:16',NULL,NULL),(101918129856319488,NULL,NULL,'认证','auth:login','AuthController.login(..)','LoginRequest[tenantCode=qingning, username=admin, password=***, deviceId=null, captchaId=825079aa0b354b33bb6ac212b400cc89, captchaCode=tttj]',0,'BusinessException: 用户名或密码错误','127.0.0.1','2e74e5d54dd24d4586c56f04f3d33b1c','2026-10-09 13:46:16','2026-10-09 13:46:16',NULL,NULL),(101918260114624512,NULL,NULL,'认证','auth:login','AuthController.login(..)','LoginRequest[tenantCode=demo, username=shopadmin, password=***, deviceId=null, captchaId=32f24ebfb82641d793417eec5cb1af21, captchaCode=bc7n]',0,'BusinessException: 验证码不正确或已失效','127.0.0.1','a796e7a2aeab4cd5b9d331468dbac162','2026-10-09 13:46:47','2026-10-09 13:46:47',NULL,NULL),(101918260617940992,101916536758996992,101916537207787520,'认证','auth:login','AuthController.login(..)','LoginRequest[tenantCode=demo, username=shopadmin, password=***, deviceId=null, captchaId=32f24ebfb82641d793417eec5cb1af21, captchaCode=bc7n]',1,NULL,'127.0.0.1','b1ff001d03c144ef828499f8cf541614','2026-10-09 13:46:47','2026-10-09 13:46:47',NULL,NULL);
/*!40000 ALTER TABLE `sys_oper_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_package`
--

DROP TABLE IF EXISTS `sys_package`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_package` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `package_name` varchar(64) NOT NULL,
  `remark` varchar(255) DEFAULT NULL,
  `status` int NOT NULL DEFAULT '1' COMMENT '0-禁用 1-正常,禁用后不可再被新租户选用,不影响已绑定该套餐的租户',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_package_name` (`package_name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='套餐表,全平台统一,不分租户';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_package`
--

LOCK TABLES `sys_package` WRITE;
/*!40000 ALTER TABLE `sys_package` DISABLE KEYS */;
INSERT INTO `sys_package` VALUES (1,'全量套餐','包含全部非平台专用菜单',1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL);
/*!40000 ALTER TABLE `sys_package` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_package_menu`
--

DROP TABLE IF EXISTS `sys_package_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_package_menu` (
  `package_id` bigint NOT NULL,
  `menu_id` bigint NOT NULL,
  PRIMARY KEY (`package_id`,`menu_id`),
  KEY `idx_menu` (`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='套餐-菜单关联表。约定:①只能包含 sys_menu.is_platform = 0 的菜单;②菜单树父链必须完整,见架构文档5.2.1';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_package_menu`
--

LOCK TABLES `sys_package_menu` WRITE;
/*!40000 ALTER TABLE `sys_package_menu` DISABLE KEYS */;
INSERT INTO `sys_package_menu` VALUES (1,1),(1,2),(1,3),(1,11),(1,12),(1,13),(1,14),(1,15),(1,16),(1,17),(1,18),(1,21),(1,22),(1,23),(1,24),(1,25),(1,26),(1,31),(1,32),(1,33),(1,34),(1,200),(1,201),(1,203),(1,204),(1,205),(1,206),(1,207),(1,208),(1,209),(1,210),(1,211),(1,212),(1,2011),(1,2012),(1,2013),(1,2014),(1,2015),(1,2021),(1,2022),(1,2023),(1,2024),(1,2031),(1,2032),(1,2033),(1,2041),(1,2042),(1,2051),(1,2052),(1,2053),(1,2061),(1,2062),(1,2063),(1,2071),(1,2072),(1,2073),(1,2074),(1,2081),(1,2082),(1,2083),(1,2091),(1,2092),(1,2093),(1,2101),(1,2102),(1,2103),(1,2111),(1,2121);
/*!40000 ALTER TABLE `sys_package_menu` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role`
--

DROP TABLE IF EXISTS `sys_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `role_key` varchar(64) NOT NULL COMMENT '角色标识,如 admin/finance',
  `role_name` varchar(64) NOT NULL,
  `data_scope` int NOT NULL DEFAULT '5' COMMENT '数据权限范围: 1-仅本人 2-本部门 3-本部门及以下 4-自定义部门 5-全部;多角色时取最大值,见架构文档5.3',
  `is_default` int NOT NULL DEFAULT '0' COMMENT '1-租户创建时自动生成的默认管理员角色,套餐变更时只自动同步这个角色,见架构文档4.8节。一个租户内至多一条为1,由建租户流程保证,不建唯一索引(应用层保证即可,允许极端情况下人工修复)。该角色的菜单由套餐同步维护,不接受人工增删,也不允许删除',
  `status` int NOT NULL DEFAULT '1',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_role_key` (`tenant_id`,`role_key`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_tenant_default` (`tenant_id`,`is_default`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role`
--

LOCK TABLES `sys_role` WRITE;
/*!40000 ALTER TABLE `sys_role` DISABLE KEYS */;
INSERT INTO `sys_role` VALUES (1,1,'platform_admin','平台管理员',5,1,1,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(101916536830300160,101916536758996992,'admin','租户管理员',5,1,1,'2026-10-09 13:39:56','2026-10-09 13:39:56',1,1);
/*!40000 ALTER TABLE `sys_role` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role_dept`
--

DROP TABLE IF EXISTS `sys_role_dept`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_dept` (
  `role_id` bigint NOT NULL,
  `dept_id` bigint NOT NULL,
  PRIMARY KEY (`role_id`,`dept_id`),
  KEY `idx_dept` (`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据权限=自定义部门 时使用';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role_dept`
--

LOCK TABLES `sys_role_dept` WRITE;
/*!40000 ALTER TABLE `sys_role_dept` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_role_dept` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_role_menu`
--

DROP TABLE IF EXISTS `sys_role_menu`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu` (
  `role_id` bigint NOT NULL,
  `menu_id` bigint NOT NULL,
  PRIMARY KEY (`role_id`,`menu_id`),
  KEY `idx_menu` (`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='idx_menu 是必须的:套餐收回是按 menu_id 批量 delete,见架构文档9.7';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_role_menu`
--

LOCK TABLES `sys_role_menu` WRITE;
/*!40000 ALTER TABLE `sys_role_menu` DISABLE KEYS */;
INSERT INTO `sys_role_menu` VALUES (1,1),(101916536830300160,1),(1,2),(101916536830300160,2),(1,3),(101916536830300160,3),(1,5),(1,6),(1,7),(1,8),(1,9),(1,10),(1,11),(101916536830300160,11),(1,12),(101916536830300160,12),(1,13),(101916536830300160,13),(1,14),(101916536830300160,14),(1,15),(101916536830300160,15),(1,16),(101916536830300160,16),(1,17),(101916536830300160,17),(1,18),(101916536830300160,18),(1,19),(1,20),(1,21),(101916536830300160,21),(1,22),(101916536830300160,22),(1,23),(101916536830300160,23),(1,24),(101916536830300160,24),(1,25),(101916536830300160,25),(1,26),(101916536830300160,26),(1,31),(101916536830300160,31),(1,32),(101916536830300160,32),(1,33),(101916536830300160,33),(1,34),(101916536830300160,34),(1,41),(1,42),(1,43),(1,44),(1,45),(1,46),(1,51),(1,52),(1,53),(1,61),(1,62),(1,63),(1,64),(1,91),(1,92),(1,93),(1,94),(1,101),(1,102),(1,191),(1,192),(1,200),(101916536830300160,200),(1,201),(101916536830300160,201),(1,203),(101916536830300160,203),(1,204),(101916536830300160,204),(1,205),(101916536830300160,205),(1,206),(101916536830300160,206),(1,207),(101916536830300160,207),(1,208),(101916536830300160,208),(1,209),(101916536830300160,209),(1,210),(101916536830300160,210),(1,211),(101916536830300160,211),(1,212),(101916536830300160,212),(1,2011),(101916536830300160,2011),(1,2012),(101916536830300160,2012),(1,2013),(101916536830300160,2013),(1,2014),(101916536830300160,2014),(1,2015),(101916536830300160,2015),(1,2021),(101916536830300160,2021),(1,2022),(101916536830300160,2022),(1,2023),(101916536830300160,2023),(1,2024),(101916536830300160,2024),(1,2031),(101916536830300160,2031),(1,2032),(101916536830300160,2032),(1,2033),(101916536830300160,2033),(1,2041),(101916536830300160,2041),(1,2042),(101916536830300160,2042),(1,2051),(101916536830300160,2051),(1,2052),(101916536830300160,2052),(1,2053),(101916536830300160,2053),(1,2061),(101916536830300160,2061),(1,2062),(101916536830300160,2062),(1,2063),(101916536830300160,2063),(1,2071),(101916536830300160,2071),(1,2072),(101916536830300160,2072),(1,2073),(101916536830300160,2073),(1,2074),(101916536830300160,2074),(1,2081),(101916536830300160,2081),(1,2082),(101916536830300160,2082),(1,2083),(101916536830300160,2083),(1,2091),(101916536830300160,2091),(1,2092),(101916536830300160,2092),(1,2093),(101916536830300160,2093),(1,2101),(101916536830300160,2101),(1,2102),(101916536830300160,2102),(1,2103),(101916536830300160,2103),(1,2111),(101916536830300160,2111),(1,2121),(101916536830300160,2121);
/*!40000 ALTER TABLE `sys_role_menu` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_task_run_log`
--

DROP TABLE IF EXISTS `sys_task_run_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_task_run_log` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `task_name` varchar(64) NOT NULL COMMENT '任务名,与 Redis 锁的键一致',
  `status` int NOT NULL COMMENT '1-成功 2-失败',
  `start_time` datetime NOT NULL,
  `end_time` datetime NOT NULL,
  `duration_ms` bigint NOT NULL,
  `error_msg` varchar(1000) DEFAULT NULL COMMENT '失败时的异常摘要',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_task_start` (`task_name`,`start_time`),
  KEY `idx_start` (`start_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='定时任务执行历史;保留期见 minimall.task-log.retain-days';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_task_run_log`
--

LOCK TABLES `sys_task_run_log` WRITE;
/*!40000 ALTER TABLE `sys_task_run_log` DISABLE KEYS */;
INSERT INTO `sys_task_run_log` VALUES (101916306240049152,'关闭超时未支付订单',1,'2026-10-09 13:39:01','2026-10-09 13:39:01',266,NULL,'2026-10-09 13:39:01','2026-10-09 13:39:01',NULL,NULL),(101916554379268096,'关闭超时未支付订单',1,'2026-10-09 13:40:00','2026-10-09 13:40:00',65,NULL,'2026-10-09 13:40:00','2026-10-09 13:40:00',NULL,NULL),(101916554421211136,'核对已关闭订单支付状态',1,'2026-10-09 13:40:00','2026-10-09 13:40:00',74,NULL,'2026-10-09 13:40:00','2026-10-09 13:40:00',NULL,NULL),(101916554484125696,'退款提交重试',1,'2026-10-09 13:40:00','2026-10-09 13:40:00',91,NULL,'2026-10-09 13:40:00','2026-10-09 13:40:00',NULL,NULL),(101916805903290368,'关闭超时未支付订单',1,'2026-10-09 13:41:00','2026-10-09 13:41:00',39,NULL,'2026-10-09 13:41:00','2026-10-09 13:41:00',NULL,NULL),(101917057737691136,'关闭超时未支付订单',1,'2026-10-09 13:42:00','2026-10-09 13:42:00',74,NULL,'2026-10-09 13:42:00','2026-10-09 13:42:00',NULL,NULL),(101917309374959616,'关闭超时未支付订单',1,'2026-10-09 13:43:00','2026-10-09 13:43:00',67,NULL,'2026-10-09 13:43:00','2026-10-09 13:43:00',NULL,NULL),(101917561024811008,'关闭超时未支付订单',1,'2026-10-09 13:44:00','2026-10-09 13:44:00',63,NULL,'2026-10-09 13:44:00','2026-10-09 13:44:00',NULL,NULL),(101917812603359232,'核对已关闭订单支付状态',1,'2026-10-09 13:45:00','2026-10-09 13:45:00',51,NULL,'2026-10-09 13:45:00','2026-10-09 13:45:00',NULL,NULL),(101917812607553536,'关闭超时未支付订单',1,'2026-10-09 13:45:00','2026-10-09 13:45:00',49,NULL,'2026-10-09 13:45:00','2026-10-09 13:45:00',NULL,NULL),(101918064311930880,'关闭超时未支付订单',1,'2026-10-09 13:46:00','2026-10-09 13:46:00',58,NULL,'2026-10-09 13:46:00','2026-10-09 13:46:00',NULL,NULL),(101918315886284800,'关闭超时未支付订单',1,'2026-10-09 13:47:00','2026-10-09 13:47:00',51,NULL,'2026-10-09 13:47:00','2026-10-09 13:47:00',NULL,NULL),(101918567661965312,'关闭超时未支付订单',1,'2026-10-09 13:48:00','2026-10-09 13:48:00',62,NULL,'2026-10-09 13:48:00','2026-10-09 13:48:00',NULL,NULL),(101918819114684416,'关闭超时未支付订单',1,'2026-10-09 13:49:00','2026-10-09 13:49:00',26,NULL,'2026-10-09 13:49:00','2026-10-09 13:49:00',NULL,NULL),(101919070886170624,'核对已关闭订单支付状态',1,'2026-10-09 13:50:00','2026-10-09 13:50:00',54,NULL,'2026-10-09 13:50:00','2026-10-09 13:50:00',NULL,NULL),(101919070928113664,'退款提交重试',1,'2026-10-09 13:50:00','2026-10-09 13:50:00',65,NULL,'2026-10-09 13:50:00','2026-10-09 13:50:00',NULL,NULL),(101919070961668096,'关闭超时未支付订单',1,'2026-10-09 13:50:00','2026-10-09 13:50:00',73,NULL,'2026-10-09 13:50:00','2026-10-09 13:50:00',NULL,NULL),(101919322519244800,'关闭超时未支付订单',1,'2026-10-09 13:51:00','2026-10-09 13:51:00',52,NULL,'2026-10-09 13:51:00','2026-10-09 13:51:00',NULL,NULL),(101919574286536704,'关闭超时未支付订单',1,'2026-10-09 13:52:00','2026-10-09 13:52:00',63,NULL,'2026-10-09 13:52:00','2026-10-09 13:52:00',NULL,NULL),(101919825777004544,'关闭超时未支付订单',1,'2026-10-09 13:53:00','2026-10-09 13:53:00',33,NULL,'2026-10-09 13:53:00','2026-10-09 13:53:00',NULL,NULL),(101920077426855936,'关闭超时未支付订单',1,'2026-10-09 13:54:00','2026-10-09 13:54:00',40,NULL,'2026-10-09 13:54:00','2026-10-09 13:54:00',NULL,NULL),(101920329198342144,'核对已关闭订单支付状态',1,'2026-10-09 13:55:00','2026-10-09 13:55:00',51,NULL,'2026-10-09 13:55:00','2026-10-09 13:55:00',NULL,NULL),(101920329223507968,'关闭超时未支付订单',1,'2026-10-09 13:55:00','2026-10-09 13:55:00',59,NULL,'2026-10-09 13:55:00','2026-10-09 13:55:00',NULL,NULL);
/*!40000 ALTER TABLE `sys_task_run_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_tenant_package_change`
--

DROP TABLE IF EXISTS `sys_tenant_package_change`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_tenant_package_change` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `trigger_type` int NOT NULL DEFAULT '1' COMMENT '触发源:1-租户换套餐(4.8) 2-平台修改套餐菜单(4.8.1) 3-预约到点自动生效',
  `old_package_id` bigint DEFAULT NULL COMMENT 'NULL 表示"变更前不限";首次绑定时也为 NULL,靠 trigger_type 区分',
  `new_package_id` bigint NOT NULL COMMENT '本条记录生效后的套餐;trigger_type=2 时与 old_package_id 相同',
  `effective_date` date NOT NULL COMMENT '生效日(只到日)。<=当天即已生效,选当天就是立即生效',
  `package_menu_ids` text COMMENT '生效时该套餐的菜单ID快照(JSON数组),用于事后回溯"当时这个租户能用什么"',
  `added_menu_ids` text COMMENT '本次新增授权的菜单ID列表(仅默认管理员角色),JSON数组',
  `revoked_menu_ids` text COMMENT '本次收回的菜单ID列表(该租户全部角色一并生效),JSON数组',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `update_by` bigint DEFAULT NULL,
  `create_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_tenant_effective` (`tenant_id`,`effective_date`,`id`),
  KEY `idx_effective` (`effective_date`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='套餐变更记录表,平台级审计数据,不分租户;当前套餐见表头注释的判定规则';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_tenant_package_change`
--

LOCK TABLES `sys_tenant_package_change` WRITE;
/*!40000 ALTER TABLE `sys_tenant_package_change` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_tenant_package_change` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_user`
--

DROP TABLE IF EXISTS `sys_user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL,
  `dept_id` bigint DEFAULT NULL,
  `username` varchar(64) NOT NULL,
  `password` varchar(128) NOT NULL COMMENT 'BCrypt hash,任何接口与日志都不得输出该字段',
  `nickname` varchar(64) DEFAULT NULL,
  `phone` varchar(20) DEFAULT NULL,
  `status` int NOT NULL DEFAULT '1' COMMENT '0-禁用 1-正常,禁用后在线会话按架构文档4.11失效',
  `is_super` int NOT NULL DEFAULT '0' COMMENT '1-平台超管:跳过租户过滤、权限短路为全量,见架构文档4.10。只能由种子数据/运维脚本设置,接口不接受该字段',
  `must_change_password` int NOT NULL DEFAULT '0' COMMENT '1-强制改密,除改密/登出/刷新权限外一律403,见架构文档7.1.2',
  `pwd_update_time` datetime DEFAULT NULL COMMENT '最近一次改密时间',
  `password_history` varchar(512) DEFAULT NULL COMMENT '最近若干次密码哈希(逗号分隔,最近一次在最前),用于禁止重复使用',
  `login_fail_count` int NOT NULL DEFAULT '0',
  `lock_time` datetime DEFAULT NULL COMMENT '登录失败锁定截止时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant_username` (`tenant_id`,`username`),
  UNIQUE KEY `uk_tenant_phone` (`tenant_id`,`phone`),
  KEY `idx_tenant` (`tenant_id`),
  KEY `idx_dept` (`dept_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='用户表。手机号可为空,空值不参与唯一约束(MySQL 唯一索引允许多个 NULL)';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_user`
--

LOCK TABLES `sys_user` WRITE;
/*!40000 ALTER TABLE `sys_user` DISABLE KEYS */;
INSERT INTO `sys_user` VALUES (1,1,1,'admin','$2a$10$kEkRE.4r9xe5C/0lR9diZOkfIMqs9XRXfXCB.zjYAw4pOMVSM6wOe','平台超管',NULL,1,1,0,'2026-10-09 13:39:56','$2a$10$olnYwrHwQDwFgYWeK7Q.tOrN0WviMndlIg3rN5LRZLk0jm2DlgIj6',0,NULL,'2026-10-09 13:38:30','2026-10-09 13:39:56',NULL,1),(101916537207787520,101916536758996992,101916536847077376,'shopadmin','$2a$10$MiBCM7FUKs6I1/E4nZtdw.FzpP5MZ4NgNPJbQFOkRLZI8QqGeFzAG','演示商户管理员',NULL,1,0,0,'2026-10-09 13:39:56',NULL,0,NULL,'2026-10-09 13:39:56','2026-10-09 13:39:56',1,1);
/*!40000 ALTER TABLE `sys_user` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_user_role`
--

DROP TABLE IF EXISTS `sys_user_role`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_role` (
  `user_id` bigint NOT NULL,
  `role_id` bigint NOT NULL,
  PRIMARY KEY (`user_id`,`role_id`),
  KEY `idx_role` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_user_role`
--

LOCK TABLES `sys_user_role` WRITE;
/*!40000 ALTER TABLE `sys_user_role` DISABLE KEYS */;
INSERT INTO `sys_user_role` VALUES (1,1),(101916537207787520,101916536830300160);
/*!40000 ALTER TABLE `sys_user_role` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `sys_wx_pay_config`
--

DROP TABLE IF EXISTS `sys_wx_pay_config`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_wx_pay_config` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_id` bigint NOT NULL COMMENT '配置归属租户',
  `pay_mode` varchar(16) NOT NULL COMMENT 'direct-普通商户 partner-服务商',
  `mch_id` varchar(32) NOT NULL COMMENT 'direct:商户号;partner:服务商商户号 sp_mchid',
  `sub_mch_id` varchar(32) DEFAULT NULL COMMENT 'partner:特约商户号 sub_mchid;direct 为空',
  `app_id` varchar(32) NOT NULL COMMENT 'direct:商户小程序 appid;partner:服务商小程序 sp_appid',
  `app_secret_enc` varchar(512) NOT NULL COMMENT 'app_id 对应的小程序 appSecret 密文',
  `sub_app_id` varchar(32) DEFAULT NULL COMMENT 'partner:特约商户小程序 sub_appid;direct 为空',
  `sub_app_secret_enc` varchar(512) DEFAULT NULL COMMENT 'sub_app_id 对应的 appSecret 密文',
  `login_app_source` varchar(8) NOT NULL DEFAULT 'app' COMMENT '登录 code2Session 用哪套凭据:app-用 app_id,sub-用 sub_app_id',
  `api_v3_key_enc` varchar(512) NOT NULL COMMENT 'APIv3 密钥(32 字节)密文,回调解密用',
  `merchant_serial_no` varchar(64) NOT NULL COMMENT '商户 API 证书序列号,请求签名用',
  `merchant_private_key_enc` text NOT NULL COMMENT '商户私钥(PKCS#8 PEM)密文',
  `platform_serial_no` varchar(64) DEFAULT NULL COMMENT '微信平台证书序列号,回调验签用',
  `platform_public_key_enc` text COMMENT '微信平台证书公钥密文(证书或 SPKI PEM)',
  `status` int NOT NULL DEFAULT '1' COMMENT '0-停用 1-正常',
  `remark` varchar(255) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_tenant` (`tenant_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='微信支付渠道配置,平台管理,每租户一行';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `sys_wx_pay_config`
--

LOCK TABLES `sys_wx_pay_config` WRITE;
/*!40000 ALTER TABLE `sys_wx_pay_config` DISABLE KEYS */;
/*!40000 ALTER TABLE `sys_wx_pay_config` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `tenant`
--

DROP TABLE IF EXISTS `tenant`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tenant` (
  `id` bigint NOT NULL COMMENT '雪花ID,应用层生成',
  `tenant_code` varchar(32) NOT NULL COMMENT '租户编码,登录时用来定位租户',
  `tenant_name` varchar(64) NOT NULL,
  `status` int NOT NULL DEFAULT '1' COMMENT '0-禁用 1-正常,禁用后在线会话按架构文档4.11失效',
  `package_id` bigint DEFAULT NULL COMMENT '套餐ID。为空表示"不限"(全部非平台菜单),仅兼容历史数据;建租户接口要求必填,见架构文档4.8。当前生效套餐,由套餐变更记录驱动(见该表注释)',
  `expire_time` datetime DEFAULT NULL COMMENT '为空表示不过期',
  `purge_at` datetime DEFAULT NULL COMMENT '注销后的数据清理时间,到点由任务物理删除该租户全部数据',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `create_by` bigint DEFAULT NULL,
  `update_by` bigint DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `tenant_code` (`tenant_code`),
  KEY `idx_package` (`package_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='租户表。注意:这里没有 is_super——"跳过租户过滤"是用户级标志,在 sys_user.is_super,见架构文档4.1/4.10';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `tenant`
--

LOCK TABLES `tenant` WRITE;
/*!40000 ALTER TABLE `tenant` DISABLE KEYS */;
INSERT INTO `tenant` VALUES (1,'platform','平台',1,1,NULL,NULL,'2026-10-09 13:38:30','2026-10-09 13:38:30',NULL,NULL),(101916536758996992,'demo','演示商户',1,1,NULL,NULL,'2026-10-09 13:39:56','2026-10-09 13:39:56',1,1);
/*!40000 ALTER TABLE `tenant` ENABLE KEYS */;
UNLOCK TABLES;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-10-09 13:58:50
