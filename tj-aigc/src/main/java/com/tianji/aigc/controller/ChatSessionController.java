package com.tianji.aigc.controller;


import com.tianji.aigc.domain.dto.ChatDTO;
import com.tianji.aigc.domain.vo.ChatEventVO;
import com.tianji.aigc.domain.vo.MessageVO;
import com.tianji.aigc.domain.vo.SessionVO;
import com.tianji.aigc.service.IChatSessionService;
import com.tianji.common.annotations.NoWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * <p>
 * 对话session 前端控制器
 * </p>
 *
 * @author kevin
 * @since 2025-12-04
 */
@RestController
@RequestMapping("/session")
@RequiredArgsConstructor
public class ChatSessionController {
    private final IChatSessionService chatSessionService;

    @PostMapping
    public SessionVO createSession(@RequestParam(value = "n", defaultValue = "3") Integer num) {
        return chatSessionService.createSession(num);
    }

    @GetMapping("/hot")
    public List<SessionVO.Example> getHotQuestion(@RequestParam(value = "n", defaultValue = "3") Integer num) {
        return chatSessionService.getHotQuestion(num);
    }

    /**
     * 查询单个历史对话详情
     *
     * @return 对话记录列表
     */
    @GetMapping("/{sessionId}")
    public List<MessageVO> getSessionDetailById(@PathVariable("sessionId") String sessionId) {
        return chatSessionService.getSessionDetailById(sessionId);
    }



}
