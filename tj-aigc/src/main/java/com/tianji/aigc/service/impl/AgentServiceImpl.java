package com.tianji.aigc.service.impl;

import cn.hutool.extra.spring.SpringUtil;
import com.tianji.aigc.agent.AbstractAgent;
import com.tianji.aigc.agent.Agent;
import com.tianji.aigc.config.SystemPromptConfig;
import com.tianji.aigc.domain.vo.ChatEventVO;
import com.tianji.aigc.enums.AgentTypeEnum;
import com.tianji.aigc.enums.ChatEventTypeEnum;
import com.tianji.aigc.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 路由工作流智能体架构：根据用户意图分析选取需要使用的智能体
 */
@Service
@RequiredArgsConstructor
public class AgentServiceImpl implements ChatService {
    private final ChatClient openAiChatClient;
    private final SystemPromptConfig systemPromptConfig;

    @Override
    public Flux<ChatEventVO> chat(String question, String sessionId) {
        // 先通过路由智能体，分析用户的意图，再执行后面的逻辑
        String result = this.findAgentByType(AgentTypeEnum.ROUTE).process(question, sessionId);
        AgentTypeEnum agentTypeEnum = AgentTypeEnum.agentNameOf(result);

        Agent agent = this.findAgentByType(agentTypeEnum);
        if (agent == null) {
            // 找不到对应的智能体，直接返回结果
            ChatEventVO chatEventVO = ChatEventVO.builder()
                    .eventType(ChatEventTypeEnum.DATA.getValue())
                    .eventData(result)
                    .build();
            return Flux.just(chatEventVO, AbstractAgent.STOP_EVENT);
        }
        // 执行智能体的逻辑
        return agent.processStream(question, sessionId);
    }

    /**
     * 根据代理类型查找对应的Agent实例
     *
     * @param agentTypeEnum 要查找的代理类型
     * @return 与给定类型匹配的Agent实例，如果未找到或类型为null则返回null
     */
    private Agent findAgentByType(AgentTypeEnum agentTypeEnum) {
        if (agentTypeEnum == null) {
            return null;
        }
        Map<String, Agent> beans = SpringUtil.getBeansOfType(Agent.class);
        // 遍历所有Agent Bean查找匹配类型
        for (Agent agent : beans.values()) {
            if (agentTypeEnum == agent.getAgentType()) {
                return agent;
            }
        }
        return null;
    }

    /**
     * 停止生成
     *
     * @param sessionId 会话ID
     */
    @Override
    public void stop(String sessionId) {
        this.findAgentByType(AgentTypeEnum.ROUTE).stop(sessionId);
    }

    @Override
    public String chatText(String question) {
        return openAiChatClient.prompt()
                .system(promptSystemSpec -> promptSystemSpec.text(this.systemPromptConfig.getTextSystemMessage().get()))
                .user(question).call().content();
    }
}