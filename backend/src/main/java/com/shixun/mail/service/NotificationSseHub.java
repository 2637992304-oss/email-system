package com.shixun.mail.service;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.shixun.concurrency.NotificationBroadcast;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
@Service
public class NotificationSseHub {
 private final Map<String,Connection> connections=new ConcurrentHashMap<>();
 private ObjectProvider<NotificationBroadcast> broadcasts;
 @Autowired public void setBroadcasts(ObjectProvider<NotificationBroadcast> b){broadcasts=b;}
 private record Connection(Long user,SseEmitter emitter){}
 public SseEmitter subscribe(Long user) {
   String id=UUID.randomUUID().toString();var emitter=new SseEmitter(30*60*1000L);
   connections.put(id,new Connection(user,emitter));
   emitter.onCompletion(()->connections.remove(id));
   emitter.onTimeout(()->{connections.remove(id);emitter.complete();});
   emitter.onError(e->connections.remove(id));
   try{emitter.send(SseEmitter.event().name("connected").data(Map.of("message","connected")));}
   catch(Exception e){connections.remove(id);emitter.complete();}
   return emitter;
 }
 public void pushNewMailNotification(Long receiver,String sender,String subject,Long mail) {
   var broadcast=broadcasts==null?null:broadcasts.getIfAvailable();
   if(broadcast!=null) broadcast.publish(receiver,sender,subject,mail);
   else pushLocal(receiver,sender,subject,mail);
 }
 public void pushLocal(Long receiver,String sender,String subject,Long mail) {
   connections.forEach((id,c)-> {
     if(!c.user().equals(receiver))return;
     try{c.emitter().send(SseEmitter.event().name("new-mail").data(Map.of("mailId",mail,"senderName",sender==null?"":sender,"subject",subject==null?"":subject)));}
     catch(Exception e){connections.remove(id,c);c.emitter().complete();}
   });
 }
 public int getOnlineCount(){return (int)connections.values().stream().map(Connection::user).distinct().count();}
}
