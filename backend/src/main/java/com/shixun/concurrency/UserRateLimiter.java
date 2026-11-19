package com.shixun.concurrency;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import com.shixun.common.exception.BusinessException;
import java.util.List;
@Service @Profile("high-concurrency")
public class UserRateLimiter {
 private final StringRedisTemplate redis;
 @Value("${mail.concurrency.rate-capacity:10}") int capacity;
 @Value("${mail.concurrency.rate-per-minute:10}") int rate;
 private static final DefaultRedisScript<Long> SCRIPT=new DefaultRedisScript<>("""
 local t=redis.call('TIME'); local now=tonumber(t[1])+tonumber(t[2])/1000000
 local cap=tonumber(ARGV[1]); local rate=tonumber(ARGV[2])/60
 local state=redis.call('HMGET',KEYS[1],'tokens','at')
 local tokens=math.min(cap,(tonumber(state[1]) or cap)+math.max(0,now-(tonumber(state[2]) or now))*rate)
 local wait=0
 if tokens>=1 then tokens=tokens-1 else wait=math.ceil((1-tokens)/rate) end
 redis.call('HSET',KEYS[1],'tokens',tokens,'at',now)
 redis.call('EXPIRE',KEYS[1],math.ceil(cap/rate)+60)
 return wait
 """,Long.class);
 public UserRateLimiter(StringRedisTemplate redis){this.redis=redis;}
 public void acquire(Long user) {
   Long wait;
   try {wait=redis.execute(SCRIPT,List.of("mail:rate:"+user),String.valueOf(capacity),String.valueOf(rate));}
   catch(Exception e){throw BusinessException.of(503,"限流服务暂不可用，请稍后重试");}
   if(wait==null) throw BusinessException.of(503,"限流服务暂不可用");
   if(wait>0) throw BusinessException.of(429,"发送过于频繁，请在 "+wait+" 秒后重试");
 }
}
