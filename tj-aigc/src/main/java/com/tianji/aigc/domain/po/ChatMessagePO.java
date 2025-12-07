package com.tianji.aigc.domain.po;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@Document(collection = "chat_message")
public class ChatMessagePO {
    @Id
    private String id;

    @Indexed // 建立索引加速 sessionId 查询
    private String sessionId; 

    private String type;    // "USER" 或 "ASSISTANT"
    private String content; // 对话内容
    
    @Indexed
    private LocalDateTime createTime; // 消息时间
}