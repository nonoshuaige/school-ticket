package com.schoolticket.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeedRabbitMQConfig {

    public static final String QUEUE_FEED_DEAD = "feed.dead.queue";
    public static final String RK_FEED_DEAD = "feed.dead";

    @Bean
    public Queue feedDeadQueue() {
        return QueueBuilder.durable(QUEUE_FEED_DEAD).build();
    }

    @Bean
    public Binding bindFeedDead(@Qualifier("feedDeadQueue") Queue feedDeadQueue,
                                DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(feedDeadQueue).to(deadLetterExchange).with(RK_FEED_DEAD);
    }

    @Bean
    public SimpleRabbitListenerContainerFactory feedListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter messageConverter,
            RabbitTemplate rabbitTemplate) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(4);
        factory.setDefaultRequeueRejected(false);
        factory.setAdviceChain(RetryInterceptorBuilder.stateless()
                .maxAttempts(3)
                .backOffOptions(500, 2.0, 5000)
                .recoverer(new RepublishMessageRecoverer(
                        rabbitTemplate,
                        RabbitMQConfig.DLX_EXCHANGE,
                        RK_FEED_DEAD))
                .build());
        return factory;
    }
}
