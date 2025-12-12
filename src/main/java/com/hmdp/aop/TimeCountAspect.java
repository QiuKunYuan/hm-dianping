package com.hmdp.aop;


import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.stereotype.Component;

@Slf4j
@Aspect
@Component
public class TimeCountAspect {

    @Pointcut("@annotation(com.hmdp.aop.TimeCountAOP)")
    public void pt(){};


    @Around("pt()")
    public Object calculaterTime(ProceedingJoinPoint joinPoint) throws Throwable{
        long begin = System.currentTimeMillis();

            Object result = joinPoint.proceed();

            long end = System.currentTimeMillis();

            log.info("方法{}执行时间：{}ms",joinPoint.getSignature().getName(),(end-begin));

        return result;
    }

}
