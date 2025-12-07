package com.tianji.aigc.memory;

import com.tianji.aigc.domain.po.ChatMessagePO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class HybridChatMemory implements ChatMemory {

    private final StringRedisTemplate stringRedisTemplate;
    private final MongoTemplate mongoTemplate;
    private final String prefix = "CHAT:";
    private final int MAX_REDIS_HISTORY = 20; // Redis只保留最近20条

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) return;

        // 1. 写入 Redis (热数据 - AI上下文)
        String redisKey = prefix + conversationId;
        var listOps = stringRedisTemplate.boundListOps(redisKey);
        messages.forEach(msg -> listOps.rightPush(MessageUtil.toJson(msg)));

        // 优化：限制Redis长度，防止Token爆炸，只保留最近N条
        if (listOps.size() > MAX_REDIS_HISTORY) {
            listOps.trim(-MAX_REDIS_HISTORY, -1);
        }
        // 建议设置过期时间，例如 3天
        stringRedisTemplate.expire(redisKey, 3, java.util.concurrent.TimeUnit.DAYS);

        // 2. 异步写入 MongoDB (冷数据 - 永久存档)
        // 实际生产建议使用线程池或MQ异步处理，这里演示直接写入
        List<ChatMessagePO> historyList = messages.stream().map(msg -> ChatMessagePO.builder()
                .sessionId(conversationId)
                .type(msg.getMessageType().getValue())
                .content(msg.getText())
                .createTime(LocalDateTime.now())
                .build()
        ).collect(Collectors.toList());

        try {
            mongoTemplate.insertAll(historyList);
        } catch (Exception e) {
            log.error("MongoDB消息归档失败: {}", e.getMessage());
            // 归档失败不应影响主流程
        }
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        // AI对话时，只从 Redis 读取热数据
        String redisKey = prefix + conversationId;
        var listOps = stringRedisTemplate.boundListOps(redisKey);
        // 获取 Redis 中的数据
        var jsonMessages = listOps.range(0, lastN);
        if (jsonMessages == null) return List.of();

        return jsonMessages.stream()
                .map(MessageUtil::toMessage)
                .collect(Collectors.toList());
    }

    @Override
    public void clear(String conversationId) {
        stringRedisTemplate.delete(prefix + conversationId);
        // MongoDB 的数据不删除，作为历史记录保留
    }
}