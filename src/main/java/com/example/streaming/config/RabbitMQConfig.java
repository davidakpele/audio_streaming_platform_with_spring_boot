package com.example.streaming.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // Constants for exchange and queues
    public static final String AUTH_EXCHANGE = "auth.notifications";

    // Auth & User Queues
    public static final String ACCOUNT_VERIFICATION_QUEUE = "account.verification";
    public static final String USER_OTP_QUEUE = "user.otp";
    public static final String RESET_PASSWORD_QUEUE = "passsword.reset";

    // Routing keys
    public static final String ROUTING_KEY_ACCOUNT_VERIFICATION = "auth.account";
    public static final String ROUTING_KEY_ACCOUNT_USER_OTP = "auth.user";
    public static final String ROUTING_KEY_RESET_PASSWORD = "auth.passsword";

    // Declare the exchange
    @Bean
    public TopicExchange accountExchange() {
        return new TopicExchange(AUTH_EXCHANGE);
    }

    // Declare the queues
    @Bean
    public Queue accountVerificationQueue() {
        return new Queue(ACCOUNT_VERIFICATION_QUEUE);
    }

    @Bean
    public Queue userOTPQueue() {
        return new Queue(USER_OTP_QUEUE);
    }

    @Bean
    public Queue resetPasswordQueue() {
        return new Queue(RESET_PASSWORD_QUEUE);
    }

    // Bindings
    @Bean
    public Binding accountVerificationBinding(Queue accountVerificationQueue, TopicExchange accountExchange) {
        return BindingBuilder.bind(accountVerificationQueue).to(accountExchange).with(ROUTING_KEY_ACCOUNT_VERIFICATION);
    }

    @Bean
    public Binding userOTPBinding(Queue userOTPQueue, TopicExchange accountExchange) {
        return BindingBuilder.bind(userOTPQueue).to(accountExchange).with(ROUTING_KEY_ACCOUNT_USER_OTP);
    }

    @Bean
    public Binding resetPasswordBinding(Queue resetPasswordQueue, TopicExchange accountExchange) {
        return BindingBuilder.bind(resetPasswordQueue).to(accountExchange).with(ROUTING_KEY_RESET_PASSWORD);
    }

    // Configure RabbitTemplate with Jackson2JsonMessageConverter for JSON serialization
    @Bean
    public Jackson2JsonMessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());
        return rabbitTemplate;
    }
}

