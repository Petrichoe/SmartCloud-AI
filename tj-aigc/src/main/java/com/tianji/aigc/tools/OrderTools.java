package com.tianji.aigc.tools;

import cn.hutool.core.collection.CollStreamUtil;
import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import com.tianji.aigc.config.ToolResultHolder;
import com.tianji.aigc.constants.Constant;
import com.tianji.aigc.tools.result.PrePlaceOrder;
import com.tianji.api.client.trade.TradeClient;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class OrderTools {

    private final TradeClient tradeClient;

    @Tool(description = Constant.Tools.PRE_PLACE_ORDER)
    public PrePlaceOrder queryOrderById(@ToolParam(description = Constant.ToolParams.COURSE_IDS) List<Number> ids,
                                        ToolContext toolContext) {
        // 设置用户ID，用于身份验证，否在在Feign调用时会出现401错误
        UserContext.setUser(Convert.toLong(toolContext.getContext().get(Constant.USER_ID)));//为什么这里不是直接UserContext.getUser()获取呢？-》ThreadLocal是单线程的，但是在SpringAi中是多线程的
        // 大模型传入的ids，可能是int类型，所以转化为long类型，再调用Feign
        var orderConfirmVO = this.tradeClient.prePlaceOrder(CollStreamUtil.toList(ids, Number::longValue));

        return Optional.ofNullable(orderConfirmVO)
                .map(PrePlaceOrder::of) // 将 VO 转为更适合 AI 理解或存储的实体
                .map(prePlaceOrder -> {
                    // 1. 生成数据存储的 Key（字段名），例如 "prePlaceOrder"
                    var field = StrUtil.lowerFirst(prePlaceOrder.getClass().getSimpleName());
                    // 2. 获取本次请求的唯一 ID
                    var requestId = Convert.toStr(toolContext.getContext().get(Constant.REQUEST_ID));
                    // 3. 【核心】把完整的订单对象存入 ToolResultHolder ,这部分是返回给前端画图的
                    ToolResultHolder.put(requestId, field, prePlaceOrder);
                    return prePlaceOrder; // 返回对象给 AI
                })
                .orElse(null);
    }


}
