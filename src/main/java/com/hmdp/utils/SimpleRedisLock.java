package com.hmdp.utils;

import cn.hutool.core.util.IdUtil;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{


    private StringRedisTemplate stringRedisTemplate;
    //业务名称 锁名称
    private String name;

    private static  final String KEY_PREFIX = "lock";
    private static final String ID_PREFIX = IdUtil.fastSimpleUUID() + "-";
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate,String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name=name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程id
        long id = Thread.currentThread().getId();
        String threadId =ID_PREFIX+ String.valueOf(id);
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue().
                setIfAbsent(KEY_PREFIX + name, threadId + "", timeoutSec, TimeUnit.SECONDS);

        //有自动拆箱风险
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {

        //获取线程标识
        long thid = Thread.currentThread().getId();
        String threadId =ID_PREFIX+ String.valueOf(thid);
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);

        //判断是否一致
        if(threadId.equals(id)) {
            //释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
