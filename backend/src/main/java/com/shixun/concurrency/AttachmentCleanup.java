package com.shixun.concurrency;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import com.shixun.attachment.util.FileStorageUtil;
@Component @Profile("high-concurrency")
public class AttachmentCleanup {
 private final JdbcTemplate db;private final TransactionTemplate tx;private final FileStorageUtil storage;
 public AttachmentCleanup(JdbcTemplate db,PlatformTransactionManager tm,FileStorageUtil storage){this.db=db;this.tx=new TransactionTemplate(tm);this.storage=storage;}
 @Scheduled(fixedDelay=60000)
 public void expire(){
  try {tx.executeWithoutResult(st->{
    var rows=db.queryForList("SELECT attachment_id,file_path FROM attachment WHERE mail_id IS NULL AND upload_time<DATE_SUB(NOW(),INTERVAL 24 HOUR) LIMIT 100 FOR UPDATE SKIP LOCKED");
    for(var row:rows){storage.delete((String)row.get("file_path"));db.update("DELETE FROM attachment WHERE attachment_id=? AND mail_id IS NULL",row.get("attachment_id"));}
  });}catch(Exception e){org.slf4j.LoggerFactory.getLogger(getClass()).warn("attachment_cleanup_failed type={}",e.getClass().getSimpleName());}
 }
}
