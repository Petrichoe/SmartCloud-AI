package com.tianji;

import com.tianji.aigc.agent.RouteAgent;
import com.tianji.aigc.enums.AgentTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class RouteAgentTest {

    @Resource
    private RouteAgent routeAgent;

    @Test
    public void testChat(){
        assertEquals(AgentTypeEnum.RECOMMEND.getAgentName(), this.routeAgent.process("最新有哪些课程", "1"));
        assertEquals(AgentTypeEnum.BUY.getAgentName(), this.routeAgent.process("下单购买这个课程", "1"));
        assertEquals(AgentTypeEnum.CONSULT.getAgentName(), this.routeAgent.process("这个课程是多少钱", "1"));
        assertEquals(AgentTypeEnum.KNOWLEDGE.getAgentName(), this.routeAgent.process("java是什么", "1"));
    }

    @Test
    public void testChat2(){
        String result1 = this.routeAgent.process("最新有哪些课程", "1");
        System.out.println("问题1返回: " + result1);

        String result2 = this.routeAgent.process("下单购买这个课程", "1");
        System.out.println("问题2返回: " + result2);

        // 然后再验证
        assertEquals(AgentTypeEnum.RECOMMEND.getAgentName(), result1);
        assertEquals(AgentTypeEnum.BUY.getAgentName(), result2);
    }

}