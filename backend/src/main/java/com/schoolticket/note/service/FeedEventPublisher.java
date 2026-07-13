package com.schoolticket.note.service;

import com.schoolticket.config.RabbitMQConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Feed 事件发布器：等待 broker confirm，避免把未确认投递当作兜底成功。 */
@Component
@RequiredArgsConstructor
public class FeedEventPublisher {

    private final RabbitTemplate rabbitTemplate;

    public void publish(String routingKey, Map<String, Object> payload) {
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE, routingKey, payload, correlation);
        try {
            CorrelationData.Confirm confirm = correlation.getFuture().get(5, TimeUnit.SECONDS);
            if (!confirm.isAck()) {
                throw new AmqpException("Feed event was nacked: " + confirm.getReason());
            }
        } catch (AmqpException e) {
            throw e;
        } catch (Exception e) {
            throw new AmqpException("Timed out waiting for Feed event confirm", e);
        }
    }
}
