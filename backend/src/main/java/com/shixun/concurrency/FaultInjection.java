package com.shixun.concurrency;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
@Component @Profile("high-concurrency")
public class FaultInjection {
 private final JdbcTemplate db;
 private final org.springframework.transaction.support.TransactionTemplate independent;
 @Value("${mail.test.fault-enabled:false}") boolean enabled;
 public FaultInjection(JdbcTemplate db,org.springframework.transaction.PlatformTransactionManager tm){this.db=db;this.independent=new org.springframework.transaction.support.TransactionTemplate(tm);this.independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);}
 public void hit(String phase) {
   if(!enabled)return;
   Integer consumed=independent.execute(st -> db.update("UPDATE delivery_test_fault SET remaining=remaining-1 WHERE phase=? AND remaining>0",phase));
   if(consumed==null || consumed!=1)return;
   if(phase.equals("processing_failure"))throw new IllegalStateException("Injected processing failure");
   Runtime.getRuntime().halt(99);
 }
}
