package com.shixun.concurrency;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.core.Message;
import com.rabbitmq.client.Channel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shixun.mail.service.SpamDeepLearningEngine;
import java.util.*;
import java.nio.file.*;
@Service @Profile("high-concurrency")
public class ModelLearning {
 public static final ThreadLocal<Boolean> WORKER=ThreadLocal.withInitial(()->false);
 private final JdbcTemplate db; private final TransactionTemplate tx; private final ObjectMapper json;
 private final ObjectProvider<SpamDeepLearningEngine> engine;
 private volatile long loadedVersion=-1;
 public long loadedVersion(){return loadedVersion;}
 public ModelLearning(JdbcTemplate db,PlatformTransactionManager tm,ObjectMapper json,ObjectProvider<SpamDeepLearningEngine> engine){
   this.db=db;this.tx=new TransactionTemplate(tm);this.json=json;this.engine=engine;
 }
 public void submit(String kind,Object values,int label,int repeat) {
   tx.executeWithoutResult(st -> {
     try {
       String payload=json.writeValueAsString(Map.of("kind",kind,"values",values,"label",label,"repeat",repeat));
       var key=new org.springframework.jdbc.support.GeneratedKeyHolder();
       db.update(c->{var ps=c.prepareStatement("INSERT INTO model_job(payload) VALUES (?)",java.sql.Statement.RETURN_GENERATED_KEYS);ps.setString(1,payload);return ps;},key);
       db.update("INSERT INTO mail_outbox(mail_id,kind) VALUES (?,'LEARN')",key.getKey().longValue());
     }catch(Exception e){throw new IllegalStateException("Learning enqueue failed",e);}
   });
 }
 @RabbitListener(queues="mail.learning",concurrency="1")
 public void consume(String eventId,Message message,Channel channel) throws Exception {
   try {
     synchronized(engine.getObject()) {
     try { tx.executeWithoutResult(st -> {
       db.queryForObject("SELECT version FROM model_version WHERE id=1 FOR UPDATE",Long.class);
       Long jobId=db.queryForObject("SELECT mail_id FROM mail_outbox WHERE id=? AND kind='LEARN'",Long.class,Long.valueOf(eventId));
       var job=db.queryForMap("SELECT payload,done FROM model_job WHERE id=? FOR UPDATE",jobId);
       if(Boolean.TRUE.equals(job.get("done")))return;
       try {
         WORKER.set(true);var model=engine.getObject();model.reloadSharedModel();
         var cmd=json.readTree(job.get("payload").toString());String kind=cmd.get("kind").asText();
         int label=cmd.get("label").asInt(),repeat=cmd.get("repeat").asInt();
         if(kind.equals("text")) model.train(cmd.get("values").get(0).asText(),cmd.get("values").get(1).asText(),label,repeat);
         else {
           List<String> words=json.convertValue(cmd.get("values"),new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});
           switch(kind){case "words" -> model.trainWithWords(words,label,repeat);case "dictionary" -> model.updateDictionaryFromWords(words,label);case "add" -> model.addLearnedWords(words,label);default -> throw new IllegalArgumentException();}
         }
         model.saveModel();
         db.update("UPDATE model_version SET version=version+1 WHERE id=1");
         db.update("UPDATE model_job SET done=TRUE WHERE id=?",jobId);
       }catch(Exception e){throw new IllegalStateException("Learning failed",e);}
       finally{WORKER.remove();}
     }); refresh(); } catch(RuntimeException e) { engine.getObject().reloadSharedModel(); throw e; }
     }
     channel.basicAck(message.getMessageProperties().getDeliveryTag(),false);
   }catch(Exception e){channel.basicNack(message.getMessageProperties().getDeliveryTag(),false,true);}
 }
 @Scheduled(fixedDelay=2000)
 public void refresh() {
   synchronized(engine.getObject()) {
   try {
     Long version=db.queryForObject("SELECT version FROM model_version WHERE id=1",Long.class);
     if(version==null || version==loadedVersion)return;
     var snapshots=db.queryForList("SELECT v.version,m.model_json FROM model_version v JOIN spam_model m ON m.id=v.id WHERE v.id=1");
     if(snapshots.isEmpty()) {
       tx.executeWithoutResult(st -> {
         db.queryForObject("SELECT version FROM model_version WHERE id=1 FOR UPDATE",Long.class);
         if(db.queryForObject("SELECT COUNT(*) FROM spam_model WHERE id=1",Integer.class)==0) engine.getObject().saveModel();
       });
       snapshots=db.queryForList("SELECT v.version,m.model_json FROM model_version v JOIN spam_model m ON m.id=v.id WHERE v.id=1");
       if(snapshots.isEmpty())return;
     }
     long snapshotVersion=((Number)snapshots.get(0).get("version")).longValue();
     String snapshotJson=(String)snapshots.get(0).get("model_json");
     Path dir=Path.of("/app/data/models");Files.createDirectories(dir);
     Path tmp=Files.createTempFile(dir,"snapshot-",".tmp");
     try {Files.writeString(tmp,snapshotJson);Files.move(tmp,dir.resolve("model-"+snapshotVersion+".json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
     finally{Files.deleteIfExists(tmp);}
     engine.getObject().restoreSnapshot(snapshotJson);loadedVersion=snapshotVersion;
   }catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("model_refresh_failed type={}",e.getClass().getSimpleName());}
   }
 }
}
