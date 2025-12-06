package com.tianji.aigc.service;

import com.tianji.aigc.domain.po.ChatSession;
import com.baomidou.mybatisplus.extension.service.IService;
import com.tianji.aigc.domain.vo.ChatEventVO;
import com.tianji.aigc.domain.vo.MessageVO;
import com.tianji.aigc.domain.vo.SessionVO;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * <p>
 * 对话session 服务类
 * </p>
 *
 * @author kevin
 * @since 2025-12-04
 */
public interface IChatSessionService extends IService<ChatSession> {

    /**
     * 创建会话session
     *
     * @param num 热门问题的数量
     * @return 会话信息
     */
    SessionVO createSession(Integer num);

    /**
     * 获取热门会话
     *
     * @return 热门会话列表
     */
    List<SessionVO.Example> getHotQuestion(Integer num);


    List<MessageVO> getSessionDetailById(String sessionId);
}
