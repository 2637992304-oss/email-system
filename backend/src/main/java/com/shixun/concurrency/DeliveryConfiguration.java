package com.shixun.concurrency;
import org.springframework.context.annotation.*;
import org.springframework.amqp.core.*;
import org.springframework.scheduling.annotation.EnableScheduling;
@Configuration @Profile("high-concurrency") @EnableScheduling
public class DeliveryConfiguration {
 @Bean public Queue mailLearningQueue() { return QueueBuilder.durable("mail.learning").withArgument("x-single-active-consumer",true).build(); }
 @Bean public Queue mailDeliveryQueue() { return new Queue("mail.delivery",true); }
 @Bean public Queue mailDeadQueue() { return new Queue("mail.dead",true); }
}
