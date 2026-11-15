package com.tianji.learning.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mq_consume_record")
public class MqConsumeRecord {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    private String consumerName;

    private String businessKey;

    private LocalDateTime createTime;
}
