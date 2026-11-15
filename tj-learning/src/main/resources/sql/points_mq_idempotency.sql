-- 在 tj_learning 数据库执行，为签到积分消费幂等建表。
CREATE TABLE IF NOT EXISTS `mq_consume_record` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `consumer_name` VARCHAR(64) NOT NULL,
    `business_key` VARCHAR(128) NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_consumer_business` (`consumer_name`, `business_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
