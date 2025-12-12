package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {


    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit timeUnit) {

        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));

        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));
    }

    //处理缓存穿透工具类方法
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallbcak,Long time, TimeUnit timeUnit){
            String key = keyPrefix + id;

            String json = stringRedisTemplate.opsForValue().get(key);

            if(StrUtil.isNotBlank(json)){
                return JSONUtil.toBean(json,type);
            }
            if(json !=null){
                return null;
            }

            R obj = dbFallbcak.apply(id);

            if(obj==null){
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }

             this.set(key,obj,time,timeUnit);
            return obj;
    }


    //处理缓存击穿工具类方法

    //获取锁
    private  boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    //构建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    //设置逻辑过期
    public <R,ID> R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,
                                           Function<ID,R> dbFallbcak,Long time, TimeUnit timeUnit){
        String key = keyPrefix + id;

        //1.提交商铺id
        String shopJSON = stringRedisTemplate.opsForValue().get(key);
        //判断是否击中缓存
        if (StrUtil.isBlank(shopJSON)) {
            return null;
        }
        //命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJSON,RedisData.class);
       R r = JSONUtil.toBean(JSONUtil.toJsonStr(redisData.getData()), type);
        //判断是否过期
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){

            //未过期，直接返回店铺
            return r;
        }
        //已过期需要缓存重建
        //缓存重建
        //1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY+id;
        boolean islock =  tryLock(lockKey);
        //2.判断是否获得锁成功
        if(islock){
            //3.成功开启独立线程实现缓存重建 线程池
            CACHE_REBUILD_EXECUTOR.execute(()->{
                try {
                    //重建缓存
                    R r1 = dbFallbcak.apply(id);
                    this.setWithLogicalExpire(key,r1,time,timeUnit );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        //4.返回过期的商铺信息
        return  r;
    }
}
