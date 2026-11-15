package com.tianji.learning.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianji.common.constants.Constant;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.config.ContainerCustomizer;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecovererWithConfirms;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.backoff.ExponentialRandomBackOffPolicy;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static com.tianji.common.constants.MqConstants.Exchange.ERROR_EXCHANGE;
import static com.tianji.common.constants.MqConstants.Key.ERROR_KEY_PREFIX;

@Slf4j
@Configuration
public class SignInRabbitMqConfig {

    private static final int MAX_ATTEMPTS = 4;
    private static final ThreadLocal<Integer> RETRY_COUNT = ThreadLocal.withInitial(() -> 0);

    @Bean(name = "signInRabbitListenerContainerFactory")
    public SimpleRabbitListenerContainerFactory signInRabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            ObjectProvider<ContainerCustomizer<SimpleMessageListenerContainer>> customizers,
            RetryOperationsInterceptor signInRetryInterceptor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        customizers.ifUnique(factory::setContainerCustomizer);
        factory.setAfterReceivePostProcessors(message -> {
            Object requestId = message.getMessageProperties().getHeader(Constant.REQUEST_ID_HEADER);
            if (requestId != null) {
                MDC.put(Constant.REQUEST_ID_HEADER, requestId.toString());
            }
            return message;
        });
        // 一般失败交给有限重试与错误队列处理；仅错误消息发布未确认时会显式要求重投原消息。
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(signInRetryInterceptor);
        return factory;
    }

    @Bean
    public RetryOperationsInterceptor signInRetryInterceptor(
            ConnectionFactory connectionFactory, ObjectMapper objectMapper, Environment environment) {
        MessageRecoverer signInMessageRecoverer = createSignInMessageRecoverer(
                connectionFactory, objectMapper, environment);
        Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
        retryableExceptions.put(TransientDataAccessException.class, true);
        retryableExceptions.put(DataAccessResourceFailureException.class, true);
        retryableExceptions.put(QueryTimeoutException.class, true);
        retryableExceptions.put(RedisConnectionFailureException.class, true);
        retryableExceptions.put(SQLTransientException.class, true);
        retryableExceptions.put(SQLRecoverableException.class, true);

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(MAX_ATTEMPTS, retryableExceptions, true, false));

        ExponentialRandomBackOffPolicy backOffPolicy = new ExponentialRandomBackOffPolicy();
        backOffPolicy.setInitialInterval(500L);
        backOffPolicy.setMultiplier(2.0);
        backOffPolicy.setMaxInterval(5_000L);
        retryTemplate.setBackOffPolicy(backOffPolicy);
        retryTemplate.registerListener(new RetryListener() {
            @Override
            public <T, E extends Throwable> void onError(
                    RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                // RetryContext 的计数包含首次调用，错误消息头记录其后的重试次数。
                RETRY_COUNT.set(Math.max(0, context.getRetryCount() - 1));
            }

            @Override
            public <T, E extends Throwable> void close(
                    RetryContext context, RetryCallback<T, E> callback, Throwable throwable) {
                RETRY_COUNT.remove();
            }
        });

        return RetryInterceptorBuilder.stateless()
                .retryOperations(retryTemplate)
                .recoverer(signInMessageRecoverer)
                .build();
    }

    private MessageRecoverer createSignInMessageRecoverer(
            ConnectionFactory connectionFactory, ObjectMapper objectMapper, Environment environment) {
        if (!(connectionFactory instanceof CachingConnectionFactory cachingConnectionFactory)) {
            throw new IllegalStateException("签到失败消息转发需要 CachingConnectionFactory 发布确认支持");
        }
        cachingConnectionFactory.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        cachingConnectionFactory.setPublisherReturns(true);

        RabbitTemplate errorTemplate = new RabbitTemplate(cachingConnectionFactory);
        errorTemplate.setMandatory(true);
        String serviceName = environment.getRequiredProperty("spring.application.name");
        String errorRoutingKey = ERROR_KEY_PREFIX + serviceName;
        RetryTemplate confirmedPublishRetryTemplate = new RetryTemplate();
        confirmedPublishRetryTemplate.setRetryPolicy(new SimpleRetryPolicy(
                3, Map.of(AmqpException.class, true), true, false));
        ExponentialRandomBackOffPolicy publishBackOffPolicy = new ExponentialRandomBackOffPolicy();
        publishBackOffPolicy.setInitialInterval(500L);
        publishBackOffPolicy.setMultiplier(2.0);
        publishBackOffPolicy.setMaxInterval(5_000L);
        confirmedPublishRetryTemplate.setBackOffPolicy(publishBackOffPolicy);

        return new RepublishMessageRecovererWithConfirms(
                errorTemplate, ERROR_EXCHANGE, errorRoutingKey, CachingConnectionFactory.ConfirmType.CORRELATED) {
            {
                setConfirmTimeout(5_000L);
            }

            @Override
            protected Map<? extends String, ?> additionalHeaders(Message message, Throwable cause) {
                Throwable failure = rootCause(cause);
                Map<String, Object> headers = new HashMap<>();
                headers.put("x-business-id", extractBusinessId(message, objectMapper));
                headers.put("x-retry-count", RETRY_COUNT.get());
                headers.put("x-failure-type", failure.getClass().getName());
                headers.put("x-failure-reason", abbreviate(
                        Objects.toString(failure.getMessage(), failure.toString()), 512));
                headers.put("x-failed-at", Instant.now().toString());
                headers.put("x-consumer-service", serviceName);
                return headers;
            }

            @Override
            public void recover(Message message, Throwable cause) {
                String businessId = extractBusinessId(message, objectMapper);
                int retryCount = RETRY_COUNT.get();
                AtomicInteger publishAttempts = new AtomicInteger();
                try {
                    // Durable queue + persistent message + confirm; retry publishing failures before requeueing source.
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    confirmedPublishRetryTemplate.execute(context -> {
                        publishAttempts.incrementAndGet();
                        super.recover(message, cause);
                        return null;
                    });
                    log.error("签到消息已转入错误队列，businessId={}，retryCount={}，failureType={}",
                            businessId, retryCount, rootCause(cause).getClass().getName(), cause);
                } catch (RuntimeException publishFailure) {
                    log.error("签到消息多次转入错误队列失败，原消息将重新投递，businessId={}，retryCount={}，publishAttempts={}",
                            businessId, retryCount, publishAttempts.get(), publishFailure);
                    throw new ImmediateRequeueAmqpException("失败消息未能确认写入错误队列", publishFailure);
                } finally {
                    RETRY_COUNT.remove();
                }
            }
        };
    }

    private static String extractBusinessId(Message message, ObjectMapper objectMapper) {
        try {
            JsonNode body = objectMapper.readTree(message.getBody());
            JsonNode eventId = body.path("eventId");
            if (!eventId.isMissingNode() && !eventId.isNull() && !eventId.asText().isBlank()) {
                return eventId.asText();
            }
            JsonNode userId = body.path("userId");
            JsonNode signDate = body.path("signDate");
            if (!userId.isMissingNode() && !signDate.isMissingNode()) {
                return "sign:" + userId.asText() + ":" + signDate.asText();
            }
        } catch (IOException ignored) {
            // Malformed payloads still retain the broker message id in the error record.
        }
        return Objects.toString(message.getMessageProperties().getMessageId(), "unknown");
    }

    private static String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
