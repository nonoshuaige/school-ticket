package com.schoolticket.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE = "school.ticket.exchange";

    public static final String QUEUE_NOTE_LIKE    = "note.like.queue";
    public static final String QUEUE_NOTE_CREATE  = "note.create.queue";
    public static final String QUEUE_FOLLOW       = "user.follow.queue";

    public static final String RK_NOTE_LIKE   = "note.like";
    public static final String RK_NOTE_CREATE = "note.create";
    public static final String RK_FOLLOW      = "user.follow";

    @Bean
    public TopicExchange exchange() {
        return new TopicExchange(EXCHANGE);
    }

    @Bean public Queue noteLikeQueue()   { return new Queue(QUEUE_NOTE_LIKE, true); }
    @Bean public Queue noteCreateQueue() { return new Queue(QUEUE_NOTE_CREATE, true); }
    @Bean public Queue followQueue()     { return new Queue(QUEUE_FOLLOW, true); }

    @Bean public Binding bindNoteLike(Queue noteLikeQueue, TopicExchange exchange)   { return BindingBuilder.bind(noteLikeQueue).to(exchange).with(RK_NOTE_LIKE); }
    @Bean public Binding bindNoteCreate(Queue noteCreateQueue, TopicExchange exchange) { return BindingBuilder.bind(noteCreateQueue).to(exchange).with(RK_NOTE_CREATE); }
    @Bean public Binding bindFollow(Queue followQueue, TopicExchange exchange)       { return BindingBuilder.bind(followQueue).to(exchange).with(RK_FOLLOW); }

    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
