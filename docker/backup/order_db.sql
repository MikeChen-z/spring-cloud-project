-- MySQL dump 10.13  Distrib 8.0.41, for Win64 (x86_64)
--
-- Host: 127.0.0.1    Database: order_db
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
-- Current Database: `order_db`
--

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `order_db` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci */ /*!80016 DEFAULT ENCRYPTION='N' */;

USE `order_db`;

--
-- Table structure for table `t_order`
--

DROP TABLE IF EXISTS `t_order`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `t_order` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` bigint NOT NULL COMMENT '下单用户 id',
  `product` varchar(128) NOT NULL DEFAULT '' COMMENT '商品名称',
  `amount` decimal(10,2) NOT NULL DEFAULT '0.00' COMMENT '订单金额（同时从用户余额扣减）',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '订单状态：0 已创建 1 已支付',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`)
) ENGINE=InnoDB AUTO_INCREMENT=21 DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单表（Seata AT 演示）';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `t_order`
--

LOCK TABLES `t_order` WRITE;
/*!40000 ALTER TABLE `t_order` DISABLE KEYS */;
INSERT INTO `t_order` VALUES (3,1,'Spring Cloud 学习手册',100.00,0,'2026-10-03 11:37:47'),(5,1,'Spring Cloud 学习手册',50.00,0,'2026-10-03 16:34:32'),(6,1,'Spring Cloud 学习手册',50.00,0,'2026-10-03 16:37:42'),(7,1,'Spring Cloud 学习手册',30.00,0,'2026-10-03 16:40:20'),(8,1,'Spring Cloud 学习手册',20.00,0,'2026-10-03 16:41:04'),(9,1,'Spring Cloud 学习手册',10.00,0,'2026-10-03 16:45:31'),(10,1,'Spring Cloud 学习手册',10.00,0,'2026-10-03 18:29:34'),(11,1,'RocketMQ 事务消息演示',20.00,0,'2026-10-03 18:33:44'),(12,1,'RocketMQ 事务消息演示',5.00,0,'2026-10-03 18:34:28'),(13,1,'Spring Cloud 学习手册',1.00,0,'2026-10-03 19:08:57'),(14,1,'Spring Cloud 学习手册',1.00,0,'2026-10-04 11:00:57'),(15,1,'Spring Cloud 学习手册',1.00,0,'2026-10-04 15:41:41'),(16,1,'Spring Cloud 学习手册',1.00,0,'2026-10-04 15:42:05'),(17,1,'Spring Cloud 学习手册',1.00,0,'2026-10-04 15:42:27'),(18,1,'RocketMQ 事务消息演示',2.00,0,'2026-10-04 15:42:46'),(19,1,'Spring Cloud 学习手册',1.00,0,'2026-10-04 18:26:46');
/*!40000 ALTER TABLE `t_order` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Table structure for table `undo_log`
--

DROP TABLE IF EXISTS `undo_log`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `undo_log` (
  `branch_id` bigint NOT NULL COMMENT 'branch transaction id',
  `xid` varchar(128) NOT NULL COMMENT 'global transaction id',
  `context` varchar(128) NOT NULL COMMENT 'undo_log context,such as serialization',
  `rollback_info` longblob NOT NULL COMMENT 'rollback info',
  `log_status` int NOT NULL COMMENT '0:normal status,1:defense status',
  `log_created` datetime(6) NOT NULL COMMENT 'create datetime',
  `log_modified` datetime(6) NOT NULL COMMENT 'modify datetime',
  UNIQUE KEY `ux_undo_log` (`xid`,`branch_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AT transaction mode undo table';
/*!40101 SET character_set_client = @saved_cs_client */;

--
-- Dumping data for table `undo_log`
--

LOCK TABLES `undo_log` WRITE;
/*!40000 ALTER TABLE `undo_log` DISABLE KEYS */;
INSERT INTO `undo_log` VALUES (7089495260665417731,'192.168.47.1:8091:7089495260665417729','serializer=jackson&compressorType=NONE',_binary '{}',1,'2026-10-03 01:23:25.388182','2026-10-03 01:23:25.388182'),(7089495260665417798,'192.168.47.1:8091:7089495260665417796','serializer=jackson&compressorType=NONE',_binary '{}',1,'2026-10-03 01:24:24.764651','2026-10-03 01:24:24.764651'),(7089495366582149149,'192.168.47.1:8091:7089495366582149121','serializer=jackson&compressorType=NONE',_binary '{}',1,'2026-10-03 11:05:26.236179','2026-10-03 11:05:26.236179'),(7089495366582155417,'192.168.47.1:8091:7089495366582155415','serializer=jackson&compressorType=NONE',_binary '{}',1,'2026-10-03 11:08:07.377951','2026-10-03 11:08:07.377951');
/*!40000 ALTER TABLE `undo_log` ENABLE KEYS */;
UNLOCK TABLES;

--
-- Dumping events for database 'order_db'
--

--
-- Dumping routines for database 'order_db'
--
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Dump completed on 2026-10-05 14:37:38
