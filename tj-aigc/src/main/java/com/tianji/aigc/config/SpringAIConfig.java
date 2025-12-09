package com.tianji.aigc.config;

import com.tianji.aigc.memory.HybridChatMemory;
import com.tianji.aigc.memory.RedisChatMemory;
import com.tianji.aigc.tools.CourseTools;
import com.tianji.aigc.tools.OrderTools;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class SpringAIConfig {

    /**
     * 配置 ChatClient
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder,
                                 Advisor loggerAdvisor,
                                 Advisor messageChatMemoryAdvisor,
                                 CourseTools courseTools,
                                 OrderTools orderTools ) {  // 日志记录器
        return chatClientBuilder
                .defaultAdvisors(loggerAdvisor, messageChatMemoryAdvisor) //添加 Advisor 功能增强
                .defaultTools(courseTools, orderTools) //添加默认工具
                .build();
    }

    /**
     * 日志记录器
     */
    @Bean
    public Advisor loggerAdvisor() {
        return new SimpleLoggerAdvisor();
    }

    // 使用Redis存储会话记忆
    /*@Bean
    public ChatMemory chatMemory() {
        return new RedisChatMemory();
    }*/


    // 使用MongoDB存储会话记忆
    @Bean
    public ChatMemory chatMemory(StringRedisTemplate stringRedisTemplate, MongoTemplate mongoTemplate) {
        // 使用新的混合存储实现
        return new HybridChatMemory(stringRedisTemplate, mongoTemplate);
    }

    /**
     * 基于Redis的会话记忆，聊天记忆整合到system message中实现多轮对话
     */
    @Bean
    public Advisor messageChatMemoryAdvisor(ChatMemory chatMemory) {
        return new MessageChatMemoryAdvisor(chatMemory);
    }
}