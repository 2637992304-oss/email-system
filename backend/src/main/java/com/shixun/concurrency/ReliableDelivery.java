package com.shixun.concurrency;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shixun.common.exception.BusinessException;
import com.shixun.common.security.AuthUserContext;
import com.shixun.mail.dto.SendMailRequest;
import com.shixun.mail.entity.Mail;
import com.shixun.mail.mapper.MailMapper;
import com.shixun.mail.service.MailService;
import com.shixun.mail.service.NotificationSseHub;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.Message;
import com.rabbitmq.client.Channel;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service @Profile("high-concurrency")
public class ReliableDelivery {
 private static final Logger log=LoggerFactory.getLogger(ReliableDelivery.class);
 @org.springframework.beans.factory.annotation.Autowired private FaultInjection faults;
 private final JdbcTemplate db; private final TransactionTemplate tx;
 private final TransactionTemplate readCommittedTx;
 private final ObjectMapper json; private final MailMapper mails; private final MailService mailService;
 private final RabbitTemplate rabbit; private final UserRateLimiter limiter; private final NotificationSseHub hub;
 @Value("${mail.concurrency.capacity:10000}") int capacity;
 @Value("${mail.concurrency.lease-seconds:60}") int lease;
 @Value("${mail.concurrency.retry-seconds:1,5,30}") int[] delays;
 public ReliableDelivery(JdbcTemplate db, PlatformTransactionManager tm,ObjectMapper json,MailMapper mails,
   MailService mailService,RabbitTemplate rabbit,UserRateLimiter limiter,NotificationSseHub hub) {
   this.db=db;this.tx=new TransactionTemplate(tm);this.json=json;this.mails=mails;this.mailService=mailService;
   this.readCommittedTx = new TransactionTemplate(tm);
   this.readCommittedTx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
   this.rabbit=rabbit;this.limiter=limiter;this.hub=hub;
 }
 public Map<String,Object> submit(SendMailRequest req,String key) {
   Long user=AuthUserContext.getUserId();
   if(user==null) throw BusinessException.of(401,"Unauthorized");
   if(key==null || !key.matches("[A-Za-z0-9._:-]{1,128}")) throw BusinessException.of(400,"需要有效的 Idempotency-Key");
   if(req==null || req.getSubject()==null || req.getSubject().isBlank() || req.getSubject().trim().length()>255)
     throw BusinessException.of(400,"请填写有效主题");
   if(req.getContent()!=null && req.getContent().getBytes(StandardCharsets.UTF_8).length>65535) throw BusinessException.of(400,"邮件正文过长");
   String fingerprint;
   try { fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(req))); }
   catch(Exception e){throw new IllegalArgumentException(e);}
   final String hash=fingerprint;
   return readCommittedTx.execute(st -> {
     if(db.queryForObject("SELECT COUNT(*) FROM user WHERE user_id=? AND status=1",Integer.class,user)!=1) throw BusinessException.of(403,"账号不可用");
     // Insert-on-duplicate acquires the per-user/key row lock before inspecting the request.
     db.update("INSERT INTO mail_submission(sender_id,request_key,fingerprint) VALUES (?,?,?) ON DUPLICATE KEY UPDATE request_key=request_key",user,key,hash);
     var existing=db.queryForMap("SELECT fingerprint,mail_id FROM mail_submission WHERE sender_id=? AND request_key=? FOR UPDATE",user,key);
     if(!hash.equals(existing.get("fingerprint"))) throw BusinessException.of(409,"同一幂等键的请求内容不同");
     if(existing.get("mail_id")!=null) return status(((Number)existing.get("mail_id")).longValue(),user,true);
     limiter.acquire(user);
     LinkedHashMap<String,String> addresses=new LinkedHashMap<>();
     addAddresses(addresses,req.getTo(),"TO");
     if(addresses.isEmpty()) throw BusinessException.of(400,"收件人不能为空");
     addAddresses(addresses,req.getCc(),"CC");addAddresses(addresses,req.getBcc(),"BCC");
     if(addresses.size()>100) throw BusinessException.of(400,"最多100位收件人");
     var users=db.queryForList("SELECT user_id,email,status FROM user WHERE email IN ("+String.join(",",Collections.nCopies(addresses.size(),"?"))+")",addresses.keySet().toArray());
     if(users.size()!=addresses.size() || users.stream().anyMatch(u -> ((Number)u.get("status")).intValue()!=1))
       throw BusinessException.of(400,"收件人不存在或账号不可用");
     List<Map<String,Object>> recipients=new ArrayList<>();
     for(var u:users) recipients.add(Map.of("id",u.get("user_id"),"type",addresses.get(((String)u.get("email")).toLowerCase(Locale.ROOT))));
     Mail mail=new Mail();mail.setSenderId(user);mail.setSubject(req.getSubject().trim());mail.setContent(req.getContent());
     mail.setHasAttachment(false);mail.setSpam(false);mail.setPriority(3);mail.setMailStatus(1);mail.setSenderDeleted(false);
     mails.insertMail(mail);Long id=mail.getMailId();
     if(req.getAttachmentIds()!=null) for(Long attachment:new LinkedHashSet<>(req.getAttachmentIds())) {
       if(db.update("UPDATE attachment SET mail_id=? WHERE attachment_id=? AND uploader_id=? AND mail_id IS NULL AND upload_time>DATE_SUB(NOW(),INTERVAL 24 HOUR)",id,attachment,user)!=1)
         throw BusinessException.of(400,"附件无效、已绑定或不属于当前用户");
       db.update("UPDATE mail SET has_attachment=TRUE WHERE mail_id=?",id);
     }
     db.update("UPDATE mail SET delivery_status='PENDING' WHERE mail_id=?",id);
     // Serialize only the final capacity check and enqueue, after preparing the mail.
     db.queryForObject("SELECT id FROM delivery_admission WHERE id=1 FOR UPDATE",Integer.class);
     if(db.queryForObject("SELECT COUNT(*) FROM delivery_task WHERE status IN ('PENDING','PROCESSING')",Long.class)>=capacity)
       throw BusinessException.of(503,"投递任务繁忙，请稍后重试");
     db.update("INSERT INTO delivery_task(mail_id,sender_id,recipients) VALUES (?,?,?)",id,user,encode(recipients));
     db.update("UPDATE mail_submission SET mail_id=? WHERE sender_id=? AND request_key=?",id,user,key);
     enqueue(id,"DELIVERY",0,0);
     return Map.of("mailId",id,"status","PENDING");
   });
 }
 private void addAddresses(Map<String,String> result,List<String> values,String type) {
   if(values!=null) for(String address:values) {
     if(address==null || address.isBlank()) continue;
     String a=address.trim().toLowerCase(Locale.ROOT);
     if(!a.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) throw BusinessException.of(400,"邮箱格式无效");
     result.putIfAbsent(a,type);
   }
 }
 public Map<String,Object> status(Long id,Long user) {return status(id,user,false);}
 private Map<String,Object> status(Long id,Long user,boolean currentRead) {
   // A retry may have established its REPEATABLE READ snapshot before waiting on the key.
   // Read the committed task with a locking current read after acquiring that key.
   var rows=db.queryForList("SELECT mail_id mailId,status,failure,accepted_at acceptedAt,delivered_at deliveredAt FROM delivery_task WHERE mail_id=? AND sender_id=?"+(currentRead?" FOR SHARE":""),id,user);
   if(rows.isEmpty()) throw BusinessException.of(404,"投递任务不存在");
   return rows.get(0);
 }
 private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalArgumentException(e);}}
 private List<Map<String,Object>> recipients(Object value) {
   try {return json.readValue(value instanceof byte[] ? new String((byte[])value,StandardCharsets.UTF_8) : value.toString(),new TypeReference<List<Map<String,Object>>>(){});}
   catch(Exception e){throw new IllegalArgumentException(e);}
 }
 private void enqueue(Long id,String kind,int attempt,int delay) {
   db.update("INSERT INTO mail_outbox(mail_id,kind,attempt,available_at) VALUES (?,?,?,TIMESTAMPADD(SECOND,?,NOW(6)))",id,kind,attempt,delay);
 }
 private record OutboxEvent(long id, long mailId, String kind) {}
 private record Publication(long id, CorrelationData correlation) {}
 @Value("${mail.concurrency.publish-batch-size:64}") int publishBatch;

 private List<OutboxEvent> claimBatch(String token) {
   return readCommittedTx.execute(status -> {
     int limit = Math.max(1, Math.min(100, publishBatch));
     List<OutboxEvent> events = db.query(
         "SELECT id,mail_id,kind FROM mail_outbox WHERE published=FALSE AND available_at<=NOW(6) "
             + "AND (lease_until IS NULL OR lease_until<NOW(6)) ORDER BY available_at LIMIT ? FOR UPDATE SKIP LOCKED",
         (row, index) -> new OutboxEvent(row.getLong("id"), row.getLong("mail_id"), row.getString("kind")), limit);
     if (!events.isEmpty()) {
       List<Object> args = new ArrayList<>(List.of(token, lease));
       for (OutboxEvent event : events) args.add(event.id());
       db.update("UPDATE mail_outbox SET lease_token=?,lease_until=TIMESTAMPADD(SECOND,?,NOW(6)) WHERE id IN ("
           + placeholders(events.size()) + ")", args.toArray());
     }
     return events;
   });
 }

 private String placeholders(int count) {
   return String.join(",", Collections.nCopies(count, "?"));
 }

 @Scheduled(fixedDelayString="${mail.concurrency.publish-delay-ms:50}")
 public void publish() {
   try {
     String token = UUID.randomUUID().toString();
     List<OutboxEvent> events = claimBatch(token);
     List<Publication> pending = new ArrayList<>();
     List<Long> completed = new ArrayList<>();
     for (OutboxEvent event : events) {
       try {
         if ("NOTIFY".equals(event.kind())) {
           notifyMail(event.mailId());
           completed.add(event.id());
           continue;
         }
         String queue = switch (event.kind()) {
           case "DEAD" -> "mail.dead";
           case "LEARN" -> "mail.learning";
           default -> "mail.delivery";
         };
         var correlation = new CorrelationData(token + ":" + event.id());
         rabbit.convertAndSend("", queue, Long.toString(event.id()), correlation);
         pending.add(new Publication(event.id(), correlation));
       } catch (Exception e) {
         log.warn("outbox_publish_failed eventId={} type={}", event.id(), e.getClass().getSimpleName());
       }
     }
     long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
     for (Publication item : pending) {
       try {
         var confirm = item.correlation().getFuture().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
         if (!confirm.isAck() || item.correlation().getReturned() != null)
           throw new IllegalStateException("publish not confirmed");
         completed.add(item.id());
       } catch (Exception e) {
         log.warn("outbox_publish_failed eventId={} type={}", item.id(), e.getClass().getSimpleName());
       }
     }
     if (!completed.isEmpty()) {
       faults.hit("publish_confirmed");
       List<Object> args = new ArrayList<>();
       args.add(token);
       args.addAll(completed);
       db.update("UPDATE mail_outbox SET published=TRUE,lease_until=NULL WHERE lease_token=? AND id IN ("
           + placeholders(completed.size()) + ")", args.toArray());
     }
   } catch (Exception e) {
     log.warn("outbox_scan_failed type={}", e.getClass().getSimpleName());
   }
 }
 @RabbitListener(queues="mail.delivery")
 public void consume(String eventId,Message message,Channel channel) throws Exception {
   try { deliver(Long.valueOf(eventId)); faults.hit("consumer_committed"); channel.basicAck(message.getMessageProperties().getDeliveryTag(),false); }
   catch(Exception e) { channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,true); }
 }
 public void deliver(Long eventId) {
   var event=db.queryForMap("SELECT * FROM mail_outbox WHERE id=? AND kind='DELIVERY'",eventId);
   Long id=((Number)event.get("mail_id")).longValue();int attempt=((Number)event.get("attempt")).intValue();
   int claimed=db.update("UPDATE delivery_task SET status='PROCESSING',lease_until=TIMESTAMPADD(SECOND,?,NOW(6)) WHERE mail_id=? AND attempt=? AND status='PENDING'",lease,id,attempt);
   if(claimed==0) return;
   faults.hit("processing_claimed");
   db.update("UPDATE mail SET delivery_status='PROCESSING' WHERE mail_id=?",id);
   try {
     tx.executeWithoutResult(st -> {
       var task=db.queryForMap("SELECT * FROM delivery_task WHERE mail_id=? FOR UPDATE",id);
       if(!"PROCESSING".equals(task.get("status")) || ((Number)task.get("attempt")).intValue()!=attempt) return;
       faults.hit("processing_failure");
       mailService.deliverAccepted(id,((Number)task.get("sender_id")).longValue(),recipients(task.get("recipients")));
       db.update("UPDATE delivery_task SET status='DELIVERED',lease_until=NULL,delivered_at=NOW(6),failure=NULL WHERE mail_id=?",id);
       db.update("UPDATE mail SET delivery_status='DELIVERED' WHERE mail_id=?",id);
       enqueue(id,"NOTIFY",attempt,0);
     });
   } catch(Exception e) {
     tx.executeWithoutResult(st -> {
       var task=db.queryForMap("SELECT * FROM delivery_task WHERE mail_id=? FOR UPDATE",id);
       if(!"PROCESSING".equals(task.get("status")) || ((Number)task.get("attempt")).intValue()!=attempt) return;
       boolean failed=attempt>=delays.length;
       db.update("UPDATE delivery_task SET status=?,attempt=attempt+1,lease_until=NULL,failure=? WHERE mail_id=?",failed?"FAILED":"PENDING","投递处理失败，可由管理员检查后重放",id);
       db.update("UPDATE mail SET delivery_status=? WHERE mail_id=?",failed?"FAILED":"PENDING",id);
       enqueue(id,failed?"DEAD":"DELIVERY",attempt+1,failed?0:delays[attempt]);
     });
     log.warn("delivery_failed mailId={} attempt={} type={}",id,attempt,e.getClass().getSimpleName());
   }
 }
 @Scheduled(fixedDelay=5000)
 public void recover() {
   try {
     var ids=db.queryForList("SELECT mail_id FROM delivery_task WHERE status='PROCESSING' AND lease_until<NOW(6) LIMIT 100",Long.class);
     for(Long id:ids) tx.executeWithoutResult(st -> {
       var rows=db.queryForList("SELECT attempt FROM delivery_task WHERE mail_id=? AND status='PROCESSING' AND lease_until<NOW(6) FOR UPDATE",id);
       if(rows.isEmpty())return;
       int attempt=((Number)rows.get(0).get("attempt")).intValue();
       db.update("UPDATE delivery_task SET status='PENDING',lease_until=NULL WHERE mail_id=?",id);
       enqueue(id,"DELIVERY",attempt,0);
     });
   }catch(Exception e){log.warn("lease_recovery_failed");}
 }
 private void notifyMail(Long id) {
   var recipients = db.queryForList(
       "SELECT r.receiver_id,m.subject,u.username FROM mail m JOIN user u ON u.user_id=m.sender_id "
           + "JOIN mail_receiver r ON r.mail_id=m.mail_id WHERE m.mail_id=? AND m.is_spam=FALSE", id);
   for (var recipient : recipients)
     hub.pushNewMailNotification(((Number)recipient.get("receiver_id")).longValue(),
         (String)recipient.get("username"), (String)recipient.get("subject"), id);
 }
}
