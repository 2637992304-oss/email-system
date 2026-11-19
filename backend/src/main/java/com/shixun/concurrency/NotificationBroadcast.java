package com.shixun.concurrency;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shixun.mail.service.NotificationSseHub;
import java.util.Map;
@Service @Profile("high-concurrency")
public class NotificationBroadcast {
 private final StringRedisTemplate redis; private final ObjectMapper json;
 public NotificationBroadcast(StringRedisTemplate redis,ObjectMapper json){this.redis=redis;this.json=json;}
 public void publish(Long receiver,String sender,String subject,Long mail) {
   try {redis.convertAndSend("mail:notifications",json.writeValueAsString(Map.of("receiver",receiver,"sender",sender,"subject",subject,"mail",mail)));}
   catch(Exception e){throw new IllegalStateException("Notification publish failed",e);}
 }
 @Bean public RedisMessageListenerContainer notifications(RedisConnectionFactory factory,NotificationSseHub hub) {
   var c=new RedisMessageListenerContainer();c.setConnectionFactory(factory);
   c.addMessageListener((msg,pattern)-> {
     try {var v=json.readTree(msg.getBody());hub.pushLocal(v.get("receiver").asLong(),v.get("sender").asText(),v.get("subject").asText(),v.get("mail").asLong());}
     catch(Exception ignored){}
   },new ChannelTopic("mail:notifications"));return c;
 }
}
