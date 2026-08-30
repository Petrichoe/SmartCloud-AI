package com.tianji.promotion.handler;

import com.tianji.promotion.domain.dto.UserCouponDTO;
import com.tianji.promotion.service.IUserCouponService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import static com.tianji.common.constants.MqConstants.Exchange.PROMOTION_EXCHANGE;
import static com.tianji.common.constants.MqConstants.Key.COUPON_RECEIVE;

@Component
@RequiredArgsConstructor
public class PromotionMqHandler {

    private final IUserCouponService userCouponService;

    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "coupon.receive.queue", durable = "true"),//创建/绑定队列 coupon.receive.queue
            exchange = @Exchange(name = PROMOTION_EXCHANGE, type = ExchangeTypes.TOPIC), //绑定到交换机 promotion.topic
            key = COUPON_RECEIVE  //绑定路由 key coupon.receive
    ))
    public void listenCouponReceiveMessage(UserCouponDTO uc){
        userCouponService.checkAndCreateUserCoupon(uc);//这个队列收到消息后，交给 listenCouponReceiveMessage 方法处理
    }
}
