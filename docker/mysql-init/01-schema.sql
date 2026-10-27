-- MySQL dump 10.13  Distrib 8.0.41, for Win64 (x86_64)
--
-- Host: localhost    Database: ai_passage_creator
-- ------------------------------------------------------
-- Server version	8.0.41

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
-- Current Database: `ai_passage_creator`
--

/*!40000 DROP DATABASE IF EXISTS `ai_passage_creator`*/;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `ai_passage_creator` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `ai_passage_creator`;

--
-- Table structure for table `agent_log`
--

DROP TABLE IF EXISTS `agent_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `agent_log` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `taskId` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务ID',
  `agentName` varchar(50) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '智能体名称',
  `startTime` datetime NOT NULL COMMENT '开始时间',
  `endTime` datetime DEFAULT NULL COMMENT '结束时间',
  `durationMs` int DEFAULT NULL COMMENT '耗时（毫秒）',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态：SUCCESS/FAILED',
  `errorMessage` text COLLATE utf8mb4_unicode_ci COMMENT '错误信息',
  `prompt` text COLLATE utf8mb4_unicode_ci COMMENT '使用的Prompt',
  `inputData` json DEFAULT NULL COMMENT '输入数据（JSON格式）',
  `outputData` json DEFAULT NULL COMMENT '输出数据（JSON格式）',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  KEY `idx_taskId` (`taskId`),
  KEY `idx_agentName` (`agentName`),
  KEY `idx_status` (`status`),
  KEY `idx_createTime` (`createTime`)
) ENGINE=InnoDB AUTO_INCREMENT=38 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='智能体执行日志表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `article`
--

DROP TABLE IF EXISTS `article`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `article` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `taskId` varchar(64) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '任务ID（UUID）',
  `userId` bigint NOT NULL COMMENT '用户ID',
  `topic` varchar(500) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '选题',
  `userDescription` text COLLATE utf8mb4_unicode_ci COMMENT '用户补充描述',
  `enabledImageMethods` json DEFAULT NULL COMMENT '允许的配图方式列表',
  `style` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '文章风格：tech/emotional/educational/humorous',
  `mainTitle` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '主标题',
  `subTitle` varchar(300) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '副标题',
  `titleOptions` json DEFAULT NULL COMMENT '标题方案列表（3-5个方案）',
  `outline` json DEFAULT NULL COMMENT '大纲（JSON格式）',
  `content` text COLLATE utf8mb4_unicode_ci COMMENT '正文（Markdown格式）',
  `fullContent` text COLLATE utf8mb4_unicode_ci COMMENT '完整图文（Markdown格式，含配图）',
  `coverImage` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '封面图 URL',
  `images` json DEFAULT NULL COMMENT '配图列表（JSON数组）',
  `status` varchar(20) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING/PROCESSING/COMPLETED/FAILED',
  `phase` varchar(50) COLLATE utf8mb4_unicode_ci DEFAULT 'PENDING' COMMENT '当前阶段：PENDING/TITLE_GENERATING/TITLE_SELECTING/OUTLINE_GENERATING/OUTLINE_EDITING/CONTENT_GENERATING',
  `errorMessage` text COLLATE utf8mb4_unicode_ci COMMENT '错误信息',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `completedTime` datetime DEFAULT NULL COMMENT '完成时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_taskId` (`taskId`),
  KEY `idx_userId` (`userId`),
  KEY `idx_status` (`status`),
  KEY `idx_createTime` (`createTime`),
  KEY `idx_userId_status` (`userId`,`status`)
) ENGINE=InnoDB AUTO_INCREMENT=68 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='文章表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `payment_record`
--

DROP TABLE IF EXISTS `payment_record`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment_record` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `userId` bigint NOT NULL COMMENT '用户ID',
  `stripeSessionId` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Stripe Checkout Session ID',
  `stripePaymentIntentId` varchar(128) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'Stripe 支付意向ID',
  `amount` decimal(10,2) NOT NULL COMMENT '金额（美元）',
  `currency` varchar(8) COLLATE utf8mb4_unicode_ci DEFAULT 'usd' COMMENT '货币',
  `status` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '状态：PENDING/SUCCEEDED/FAILED/REFUNDED',
  `productType` varchar(32) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '产品类型：VIP_PERMANENT',
  `description` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '描述',
  `refundTime` datetime DEFAULT NULL COMMENT '退款时间',
  `refundReason` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '退款原因',
  `createTime` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_userId` (`userId`),
  KEY `idx_stripeSessionId` (`stripeSessionId`),
  KEY `idx_status` (`status`),
  KEY `idx_createTime` (`createTime`)
) ENGINE=InnoDB AUTO_INCREMENT=6 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='支付记录表';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Table structure for table `user`
--

DROP TABLE IF EXISTS `user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT 'id',
  `userAccount` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '账号',
  `userPassword` varchar(512) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '密码',
  `userName` varchar(256) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户昵称',
  `userAvatar` varchar(1024) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户头像',
  `userProfile` varchar(512) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '用户简介',
  `userRole` varchar(256) COLLATE utf8mb4_unicode_ci NOT NULL DEFAULT 'user' COMMENT '用户角色：user/admin/vip',
  `quota` int NOT NULL DEFAULT '5' COMMENT '剩余配额',
  `editTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '编辑时间',
  `createTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updateTime` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `isDelete` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除',
  `vipTime` datetime DEFAULT NULL COMMENT '成为会员时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_userAccount` (`userAccount`),
  KEY `idx_userName` (`userName`)
) ENGINE=InnoDB AUTO_INCREMENT=472686387795783681 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-10-27 22:14:46
