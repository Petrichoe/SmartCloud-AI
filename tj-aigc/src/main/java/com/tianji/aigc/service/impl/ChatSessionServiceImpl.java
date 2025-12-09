package com.tianji.aigc.service.impl;

import cn.hutool.core.stream.StreamUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import com.tianji.aigc.config.SessionProperties;
import com.tianji.aigc.domain.po.ChatMessagePO;
import com.tianji.aigc.domain.po.ChatSession;
import com.tianji.aigc.domain.vo.ChatEventVO;
import com.tianji.aigc.domain.vo.MessageVO;
import com.tianji.aigc.domain.vo.SessionVO;
import com.tianji.aigc.enums.MessageTypeEnum;
import com.tianji.aigc.mapper.ChatSessionMapper;
import com.tianji.aigc.service.ChatService;
import com.tianji.aigc.service.IChatSessionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * <p>
 * 对话session 服务实现类
 * </p>
 *
 * @author kevin
 * @since 2025-12-04
 */
@Service
@RequiredArgsConstructor
public class ChatSessionServiceImpl extends ServiceImpl<ChatSessionMapper, ChatSession> implements IChatSessionService {

    private final SessionProperties sessionProperties;

    private final ChatMemory chatMemory;

    // 历史消息数量，默认1000条
    public static final int HISTORY_MESSAGE_COUNT = 1000;

    // 注入 MongoTemplate
    private final MongoTemplate mongoTemplate;

    @Override
    public SessionVO createSession(Integer num) {
        SessionVO sessionVO = BeanUtils.copyBean(sessionProperties, SessionVO.class);
        // 随机获取examples
        sessionVO.setExamples(RandomUtil.randomEleList(sessionProperties.getExamples(), num));

        // 随机生成sessionId
        sessionVO.setSessionId(IdUtil.fastSimpleUUID());

        //持久化到数据库
        ChatSession chatSession=ChatSession.builder()
                .sessionId(sessionVO.getSessionId())
                .userId(UserContext.getUser())
                .build();
        super.save(chatSession);

        return sessionVO;
    }

    @Override
    public List<SessionVO.Example> getHotQuestion(Integer num) {
        List<SessionVO.Example> examples = RandomUtil.randomEleList(sessionProperties.getExamples(), num);

        return examples;
    }

    @Override
    public List<MessageVO> getSessionDetailById(String sessionId) {
        // 根据会话ID获取对话ID
        String conversationId = ChatService.getConversationId(sessionId);
        // --- 改造开始：改为查 MongoDB ---
        Query query = Query.query(Criteria.where("sessionId").is(conversationId))
                .with(Sort.by(Sort.Order.asc("createTime"))); // 按时间正序

        List<ChatMessagePO> historyList = mongoTemplate.find(query, ChatMessagePO.class);
        return historyList.stream()
                .map(po -> MessageVO.builder()
                        .type(MessageTypeEnum.valueOf(po.getType().toUpperCase()))
                        .content(po.getContent())
                        .build())
                .toList();
        // --- 改造结束 ---

        /*// 从Redis中获取历史消息
        List<Message> messageList = this.chatMemory.get(conversationId, HISTORY_MESSAGE_COUNT);
        // 过滤并转换消息列表
        return StreamUtil.of(messageList)
                // 过滤掉非用户消息和助手消息
                .filter(message -> message.getMessageType() == MessageType.ASSISTANT || message.getMessageType() == MessageType.USER)
                // 转换为MessageVO对象
                .map(message -> MessageVO.builder()
                        .content(message.getText())
                        .type(MessageTypeEnum.valueOf(message.getMessageType().name()))
                        .build())
                .toList();*/
    }


}
