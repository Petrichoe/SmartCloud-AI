package com.tianji.aigc.service.impl;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.stream.StreamUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import com.tianji.aigc.config.SessionProperties;
import com.tianji.aigc.domain.po.ChatMessagePO;
import com.tianji.aigc.domain.po.ChatSession;
import com.tianji.aigc.domain.vo.ChatEventVO;
import com.tianji.aigc.domain.vo.ChatSessionVO;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

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
@Slf4j
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
        log.info("查询会话详情 - sessionId: {}, conversationId: {}", sessionId, conversationId);

        // --- 改造开始：改为查 MongoDB ---
        Query query = Query.query(Criteria.where("sessionId").is(conversationId))
                .with(Sort.by(Sort.Order.asc("createTime"))); // 按时间正序

        List<ChatMessagePO> historyList = mongoTemplate.find(query, ChatMessagePO.class);
        log.info("从 MongoDB 查询到 {} 条历史记录", historyList.size());

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

    /**
     * 异步更新聊天会话的标题
     *
     * @param sessionId 会话ID，用于标识特定的聊天会话
     * @param title     新的会话标题，如果为空则不进行更新
     * @param userId    用户ID
     */
    @Async
    @Override
    public void update(String sessionId, String title, Long userId) {
        // 查询符合条件的聊天会话列表
        List<ChatSession> list = super.lambdaQuery()
                .eq(ChatSession::getSessionId, sessionId)
                .eq(ChatSession::getUserId, userId)
                .list();
        // 如果列表为空，直接返回，无需进一步处理
        if (CollUtil.isEmpty(list)) {
            return;
        }

        // 获取列表中的第一个聊天会话实例
        ChatSession chatSession = list.get(0);
        // 如果聊天会话的标题为空，并且新标题不为空，则更新标题
        if (StrUtil.isEmpty(chatSession.getTitle()) && !StrUtil.isEmpty(title)) {
            chatSession.setTitle(StrUtil.sub(title, 0, 100));
        }
        // 设置更新字段为updateTime为当前时间
        chatSession.setUpdateTime(LocalDateTimeUtil.now());
        // 更新数据库中的聊天会话信息
        super.updateById(chatSession);
    }

    @Override
    public Map<String, List<ChatSessionVO>> queryHistorySession() {
        Long userId = UserContext.getUser();
        // 查询历史会话，限制返回条数
        List<ChatSession> list = super.lambdaQuery()
                .eq(ChatSession::getUserId, UserContext.getUser())
                .isNotNull(ChatSession::getTitle)
                .orderByDesc(ChatSession::getUpdateTime)
                .last("LIMIT 30")
                .list();

        if (CollUtil.isEmpty(list)) {
            log.info("No chat sessions found for user: {}", userId);
            return Map.of();
        }


        // 转换为 ChatSessionVO 列表
        List<ChatSessionVO> chatSessionVOS = CollStreamUtil.toList(list, chatSession ->
                ChatSessionVO.builder()
                        .sessionId(chatSession.getSessionId())
                        .title(chatSession.getTitle())
                        .updateTime(chatSession.getUpdateTime())
                        .build()
        );

        final String TODAY = "当天";
        final String LAST_30_DAYS = "最近30天";
        final String LAST_YEAR = "最近1年";
        final String MORE_THAN_YEAR = "1年以上";

        // 当前时间
        LocalDate now = LocalDateTime.now().toLocalDate();

        // 按照更新时间分组
        return CollStreamUtil.groupByKey(chatSessionVOS, vo -> {
            // 计算两个日期之间的天数差
            long between = Math.abs(ChronoUnit.DAYS.between(vo.getUpdateTime().toLocalDate(), now));
            if (between == 0) {
                return TODAY;
            } else if (between <= 30) {
                return LAST_30_DAYS;
            } else if (between <= 365) {
                return LAST_YEAR;
            } else {
                return MORE_THAN_YEAR;
            }
        });

    }

    @Override
    public void deleteHistorySession(String sessionId) {
        // 1. 删除 MySQL 中的会话记录
        LambdaQueryWrapper<ChatSession> queryWrapper = Wrappers.<ChatSession>lambdaQuery()
                .eq(ChatSession::getSessionId, sessionId)
                .eq(ChatSession::getUserId, UserContext.getUser());
        super.remove(queryWrapper);

        // 2. 获取对话ID（用于删除 Redis 和 MongoDB）
        String conversationId = ChatService.getConversationId(sessionId);

        // 3. 删除 Redis 中的对话缓存（热数据）
        this.chatMemory.clear(conversationId);

        // 4. 删除 MongoDB 中的历史消息（冷数据）
        try {
            Query mongoQuery = Query.query(Criteria.where("sessionId").is(conversationId));
            long deletedCount = mongoTemplate.remove(mongoQuery, ChatMessagePO.class).getDeletedCount();
            log.info("删除会话成功 - sessionId: {}, conversationId: {}, MongoDB删除消息数: {}",
                    sessionId, conversationId, deletedCount);
        } catch (Exception e) {
            log.error("删除 MongoDB 历史消息失败 - conversationId: {}", conversationId, e);
            // 注意：即使 MongoDB 删除失败，MySQL 和 Redis 已经删除了
            // 可以根据业务需求决定是否抛出异常回滚
        }
    }

    @Override
    public void updateTitle(String sessionId, String title) {
        //更新数据
        super.lambdaUpdate()
                // 设置更新条件, 更新字段为title(最多设置前100个字符)，更新条件为sessionId和userId
                .set(ChatSession::getTitle, StrUtil.sub(title, 0, 100))
                .eq(ChatSession::getSessionId, sessionId)
                .eq(ChatSession::getUserId, UserContext.getUser())
                .update();
    }


}
