-- NB5 Phase 2 스파이크용 스키마 — notenest_nb5 의 DDL(데이터 없음). 2026-09-26 mariadb-dump --no-data 로 추출.
-- Hibernate ddl-auto 로 만든 스키마 + nb5-01 STEP 2 컬럼(bpm, musical_key). CHECK 제약은 없다(Phase 1 §6 미해결).
/*M!999999\- enable the sandbox mode */ 

/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;

CREATE DATABASE /*!32312 IF NOT EXISTS*/ `notenest_nb5` /*!40100 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci */;

USE `notenest_nb5`;
DROP TABLE IF EXISTS `bid`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `bid` (
  `bid_uuid` uuid NOT NULL,
  `bidder_email_sent` bit(1) NOT NULL,
  `composer_email_sent` bit(1) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `imp_uid` varchar(255) DEFAULT NULL,
  `price` bigint(20) DEFAULT NULL,
  `status` varchar(255) DEFAULT NULL,
  `music_uuid` uuid NOT NULL,
  `user_uuid` uuid NOT NULL,
  PRIMARY KEY (`bid_uuid`),
  KEY `FK8lm82jbdd1byj7581q5dl600w` (`music_uuid`),
  KEY `FKoc0hpt83ul78wcxb0v9gju7fi` (`user_uuid`),
  CONSTRAINT `FK8lm82jbdd1byj7581q5dl600w` FOREIGN KEY (`music_uuid`) REFERENCES `music` (`music_uuid`),
  CONSTRAINT `FKoc0hpt83ul78wcxb0v9gju7fi` FOREIGN KEY (`user_uuid`) REFERENCES `user` (`user_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `board`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `board` (
  `board_uuid` uuid NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `updated_time` datetime(6) DEFAULT NULL,
  `content` varchar(255) DEFAULT NULL,
  `hits` int(11) DEFAULT NULL,
  `title` varchar(255) DEFAULT NULL,
  `music_uuid` uuid DEFAULT NULL,
  `user_uuid` uuid DEFAULT NULL,
  PRIMARY KEY (`board_uuid`),
  KEY `FK6sr2qv8kbwlqtkubutxdjolr8` (`music_uuid`),
  KEY `FKdo1ssmnghv4k88p1mshx1f51w` (`user_uuid`),
  CONSTRAINT `FK6sr2qv8kbwlqtkubutxdjolr8` FOREIGN KEY (`music_uuid`) REFERENCES `music` (`music_uuid`),
  CONSTRAINT `FKdo1ssmnghv4k88p1mshx1f51w` FOREIGN KEY (`user_uuid`) REFERENCES `user` (`user_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `comments`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `comments` (
  `comment_uuid` uuid NOT NULL,
  `created_time` datetime(6) DEFAULT NULL,
  `updated_time` datetime(6) DEFAULT NULL,
  `content` varchar(255) DEFAULT NULL,
  `is_composer` bit(1) DEFAULT NULL,
  `board_uuid` uuid DEFAULT NULL,
  `user_uuid` uuid DEFAULT NULL,
  PRIMARY KEY (`comment_uuid`),
  KEY `FKtk06sobf2baqwbsjlg4vxqngc` (`board_uuid`),
  KEY `FKht8vffvbk72kvx93e8tu0mbcy` (`user_uuid`),
  CONSTRAINT `FKht8vffvbk72kvx93e8tu0mbcy` FOREIGN KEY (`user_uuid`) REFERENCES `user` (`user_uuid`),
  CONSTRAINT `FKtk06sobf2baqwbsjlg4vxqngc` FOREIGN KEY (`board_uuid`) REFERENCES `board` (`board_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `composer`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `composer` (
  `composer` varchar(255) NOT NULL,
  `entry_count` bigint(20) DEFAULT NULL,
  `hit_song` bit(1) DEFAULT NULL,
  `popular` bit(1) DEFAULT NULL,
  `steady_work` bit(1) DEFAULT NULL,
  PRIMARY KEY (`composer`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `likes`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `likes` (
  `like_uuid` uuid NOT NULL,
  `music_uuid` uuid DEFAULT NULL,
  `user_uuid` uuid DEFAULT NULL,
  PRIMARY KEY (`like_uuid`),
  KEY `FK6wh1e2d8ibic8yx05fsq9hcel` (`music_uuid`),
  KEY `FK5oe97w5b2f2uk94wrb1cwou5j` (`user_uuid`),
  CONSTRAINT `FK5oe97w5b2f2uk94wrb1cwou5j` FOREIGN KEY (`user_uuid`) REFERENCES `user` (`user_uuid`),
  CONSTRAINT `FK6wh1e2d8ibic8yx05fsq9hcel` FOREIGN KEY (`music_uuid`) REFERENCES `music` (`music_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `music`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `music` (
  `music_uuid` uuid NOT NULL,
  `auction_end_time` datetime(6) DEFAULT NULL,
  `auction_failure_email_sent` bit(1) NOT NULL,
  `bpm` int(11) DEFAULT NULL,
  `cover_content_type` varchar(100) DEFAULT NULL,
  `cover_object_key` varchar(512) DEFAULT NULL,
  `cover_original_name` varchar(255) DEFAULT NULL,
  `cover_size` bigint(20) DEFAULT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `current_highest_bid` bigint(20) DEFAULT NULL,
  `details` text DEFAULT NULL,
  `full_demo_content_type` varchar(100) DEFAULT NULL,
  `full_demo_object_key` varchar(512) DEFAULT NULL,
  `full_demo_original_name` varchar(255) DEFAULT NULL,
  `full_demo_size` bigint(20) DEFAULT NULL,
  `hashtag` varchar(255) DEFAULT NULL,
  `hit_song_composer` bit(1) NOT NULL,
  `like_count` int(11) DEFAULT NULL,
  `major_genre` varchar(255) DEFAULT NULL,
  `music_period` int(11) DEFAULT NULL,
  `musical_key` varchar(20) DEFAULT NULL,
  `popular_composer` bit(1) NOT NULL,
  `preview_content_type` varchar(100) DEFAULT NULL,
  `preview_object_key` varchar(512) DEFAULT NULL,
  `preview_original_name` varchar(255) DEFAULT NULL,
  `preview_size` bigint(20) DEFAULT NULL,
  `show_all_bids` bit(1) NOT NULL,
  `starting_price` bigint(20) DEFAULT NULL,
  `status` int(11) NOT NULL,
  `steady_work_composer` bit(1) NOT NULL,
  `subtitle` varchar(255) DEFAULT NULL,
  `title` varchar(255) DEFAULT NULL,
  `user_uuid` uuid NOT NULL,
  PRIMARY KEY (`music_uuid`),
  KEY `idx_music_auction_end_time` (`auction_end_time`),
  KEY `FK3bqvemfv8a9dhx2f40iq8i83l` (`user_uuid`),
  CONSTRAINT `FK3bqvemfv8a9dhx2f40iq8i83l` FOREIGN KEY (`user_uuid`) REFERENCES `user` (`user_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `payment`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `payment` (
  `payment_uuid` uuid NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `imp_uid` varchar(255) NOT NULL,
  `price` bigint(20) NOT NULL,
  `status` varchar(255) NOT NULL,
  `bid_uuid` uuid NOT NULL,
  PRIMARY KEY (`payment_uuid`),
  UNIQUE KEY `UK_7x1de792e8b701boalonunubs` (`bid_uuid`),
  CONSTRAINT `FKk9cryf6ectd980acg2kkwjxko` FOREIGN KEY (`bid_uuid`) REFERENCES `bid` (`bid_uuid`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
DROP TABLE IF EXISTS `user`;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!40101 SET character_set_client = utf8mb4 */;
CREATE TABLE `user` (
  `user_uuid` uuid NOT NULL,
  `agreement` bit(1) DEFAULT NULL,
  `email` varchar(255) DEFAULT NULL,
  `email_verified` bit(1) DEFAULT NULL,
  `name` varchar(255) DEFAULT NULL,
  `nickname` varchar(255) DEFAULT NULL,
  `password` varchar(255) DEFAULT NULL,
  `phone_no` varchar(255) DEFAULT NULL,
  `role` varchar(255) DEFAULT NULL,
  PRIMARY KEY (`user_uuid`),
  UNIQUE KEY `UK_ob8kqyqqgmefl0aco34akdtpe` (`email`),
  UNIQUE KEY `UK_n4swgcf30j6bmtb4l4cjryuym` (`nickname`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

