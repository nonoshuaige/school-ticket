package com.schoolticket.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "school.ticket.exchange";
    public static final String DLX_EXCHANGE = "school.ticket.dlx";

    public static final String QUEUE_NOTE_LIKE    = "note.like.queue";
    public static final String QUEUE_NOTE_CREATE  = "note.create.queue";
    public static final String QUEUE_NOTE_DELETE  = "note.delete.queue";
    public static final String QUEUE_FOLLOW        = "user.follow.queue";
    public static final String QUEUE_ORDER_CREATE  = "order.create.queue";
    public static final String QUEUE_ORDER_DELAY   = "order.delay.queue";
    public static final String QUEUE_ORDER_CLOSE   = "order.close.queue";
    public static final String QUEUE_ORDER_DEAD    = "order.dead.queue";

    public static final String RK_NOTE_LIKE    = "note.like";
    public static final String RK_NOTE_CREATE  = "note.create";
    public static final String RK_NOTE_DELETE  = "note.delete";
    public static final String RK_FOLLOW       = "user.follow";
    public static final String RK_ORDER_CREATE = "order.create";
    public static final String RK_ORDER_DELAY  = "order.delay";
    public static final String RK_ORDER_CLOSE  = "order.close";
    public static final String RK_ORDER_DEAD   = "order.dead";

    private static final int ORDER_DELAY_TTL_MS = 15 * 60 * 1000;

    @Bean
    public TopicExchange exchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    @Bean public Queue noteLikeQueue()   { return new Queue(QUEUE_NOTE_LIKE, true); }
    @Bean public Queue noteCreateQueue() { return new Queue(QUEUE_NOTE_CREATE, true); }
    @Bean public Queue noteDeleteQueue() { return new Queue(QUEUE_NOTE_DELETE, true); }
    @Bean public Queue followQueue()        { return new Queue(QUEUE_FOLLOW, true); }

    @Bean
    public Queue orderCreateQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_CREATE).build();
    }

    @Bean
    public Queue orderDelayQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_DELAY)
                .ttl(ORDER_DELAY_TTL_MS)
                .deadLetterExchange(EXCHANGE)
                .deadLetterRoutingKey(RK_ORDER_CLOSE)
                .build();
    }

    @Bean
    public Queue orderCloseQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_CLOSE).build();
    }

    @Bean
    public Queue orderDeadQueue() {
        return QueueBuilder.durable(QUEUE_ORDER_DEAD).build();
    }

    @Bean public Binding bindNoteLike(Queue noteLikeQueue, TopicExchange exchange)          { return BindingBuilder.bind(noteLikeQueue).to(exchange).with(RK_NOTE_LIKE); }
    @Bean public Binding bindNoteCreate(Queue noteCreateQueue, TopicExchange exchange)      { return BindingBuilder.bind(noteCreateQueue).to(exchange).with(RK_NOTE_CREATE); }
    @Bean public Binding bindNoteDelete(Queue noteDeleteQueue, TopicExchange exchange)      { return BindingBuilder.bind(noteDeleteQueue).to(exchange).with(RK_NOTE_DELETE); }
    @Bean public Binding bindFollow(Queue followQueue, TopicExchange exchange)              { return BindingBuilder.bind(followQueue).to(exchange).with(RK_FOLLOW); }
    @Bean public Binding bindOrderCreate(Queue orderCreateQueue, TopicExchange exchange)    { return BindingBuilder.bind(orderCreateQueue).to(exchange).with(RK_ORDER_CREATE); }
    @Bean public Binding bindOrderDelay(Queue orderDelayQueue, TopicExchange exchange)      { return BindingBuilder.bind(orderDelayQueue).to(exchange).with(RK_ORDER_DELAY); }
    @Bean public Binding bindOrderClose(Queue orderCloseQueue, TopicExchange exchange)      { return BindingBuilder.bind(orderCloseQueue).to(exchange).with(RK_ORDER_CLOSE); }
    @Bean public Binding bindOrderDead(Queue orderDeadQueue, DirectExchange deadLetterExchange) { return BindingBuilder.bind(orderDeadQueue).to(deadLetterExchange).with(RK_ORDER_DEAD); }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public SimpleRabbitListenerContainerFactory orderRabbitListenerContainerFactory(
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
                .backOffOptions(1000, 2.0, 5000)
                .recoverer(new RepublishMessageRecoverer(rabbitTemplate, DLX_EXCHANGE, RK_ORDER_DEAD))
                .build());
        return factory;
    }
}
