package com.shixun.concurrency;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.IOException;
@Component @Profile("high-concurrency")
public class TestInstanceHeaders extends OncePerRequestFilter {
 private final ModelLearning model;
 @Value("${mail.test.fault-enabled:false}") boolean enabled;
 public TestInstanceHeaders(ModelLearning model){this.model=model;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  if(enabled){response.setHeader("X-Instance",System.getenv().getOrDefault("HOSTNAME","local"));response.setHeader("X-Model-Version",String.valueOf(model.loadedVersion()));}
  chain.doFilter(request,response);
 }
}
