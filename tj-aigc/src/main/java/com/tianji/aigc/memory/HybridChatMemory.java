package com.tianji.aigc.memory;

import com.tianji.aigc.domain.po.ChatMessagePO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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


    /**
     * 优化对话记录，删除最后的2条消息（路由智能体的中间转发消息）
     * 同时清理 Redis 和 MongoDB 中的数据，保证数据一致性
     *
     * @param conversationId 对话的唯一标识符
     */
    public void optimization(String conversationId) {
        // 1. 删除 Redis 中的最后2条
        String redisKey = prefix + conversationId;
        var listOps = stringRedisTemplate.boundListOps(redisKey);
        listOps.rightPop(2);

        // 2. 删除 MongoDB 中的最后2条
        try {
            // 查询该会话最近的2条消息
            var query = new Query();
            query.addCriteria(Criteria.where("sessionId").is(conversationId));
            query.with(Sort.by(Sort.Direction.DESC, "createTime"));
            query.limit(2);

            List<ChatMessagePO> lastTwoMessages = mongoTemplate.find(query, ChatMessagePO.class);

            // 批量删除这2条消息
            if (!lastTwoMessages.isEmpty()) {
                List<String> idsToDelete = lastTwoMessages.stream()
                        .map(ChatMessagePO::getId)
                        .collect(Collectors.toList());

                var deleteQuery = new Query();
                deleteQuery.addCriteria(Criteria.where("id").in(idsToDelete));
                mongoTemplate.remove(deleteQuery, ChatMessagePO.class);

                log.debug("MongoDB优化: 删除会话 {} 的最后2条消息，IDs: {}", conversationId, idsToDelete);
            }
        } catch (Exception e) {
            log.error("MongoDB优化失败: conversationId={}, error={}", conversationId, e.getMessage());
            // 优化失败不应影响主流程
        }
    }
}