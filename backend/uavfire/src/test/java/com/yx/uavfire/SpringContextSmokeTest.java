package com.yx.uavfire;

import com.yx.uavfire.component.ApplicationBootInitial;
import com.yx.uavfire.fc100.deliverysync.controller.DeliveryController;
import com.yx.uavfire.fc100.operation.command.CommandQueueService;
import com.yx.uavfire.fc100.operation.lease.ResourceLeaseService;
import com.yx.uavfire.fc100.operation.preflight.PreflightRuleEngine;
import com.yx.uavfire.fc100.operation.service.OperationIncidentService;
import com.yx.uavfire.fc100.payload.service.PayloadReleasePolicyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Spring 上下文冒烟测试：完整刷新 ApplicationContext，防止“多构造器未标 @Autowired”
 * 之类只有真实装配才能暴露的回归（见 PayloadReleasePolicyService / PreflightRuleEngine 的历史问题）。
 *
 * 外部依赖替身说明：
 * - DataSource：Druid 的 initMethod="init" 会按 initial-size 真连 MySQL，用 mock 替换；
 *   MyBatis-Plus 构建 SqlSessionFactory 不需要真实连接。
 * - mqttInbound / waylineAgentMqttAdapter：两个 MQTT inbound 适配器 autoStartup=true，
 *   启动即连 broker，用 mock 替换（mock 的 isAutoStartup() 返回 false，不会启动）。
 * - ApplicationBootInitial：CommandLineRunner，启动即扫描 Redis 在线设备，用 mock 替换。
 * Redis / MinIO / OSS 客户端均为惰性连接，无需替身。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SpringContextSmokeTest {

    @MockBean
    private DataSource dataSource;

    @MockBean(name = "mqttInbound")
    private MqttPahoMessageDrivenChannelAdapter mqttInbound;

    @MockBean(name = "waylineAgentMqttAdapter")
    private MqttPahoMessageDrivenChannelAdapter waylineAgentMqttAdapter;

    @MockBean
    private ApplicationBootInitial applicationBootInitial;

    @Autowired
    private ApplicationContext context;

    @Test
    void contextRefreshesAndCriticalBeansAreWired() {
        assertNotNull(context.getBean(DeliveryController.class));
        assertNotNull(context.getBean(PayloadReleasePolicyService.class));
        assertNotNull(context.getBean(PreflightRuleEngine.class));
        assertNotNull(context.getBean(CommandQueueService.class));
        assertNotNull(context.getBean(ResourceLeaseService.class));
        assertNotNull(context.getBean(OperationIncidentService.class));
    }
}
