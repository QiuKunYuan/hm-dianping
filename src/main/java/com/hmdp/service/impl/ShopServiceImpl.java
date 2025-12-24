package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.aop.TimeCountAOP;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import jakarta.annotation.Resource;
import jodd.util.StringUtil;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.redisson.api.RBloomFilter;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RBloomFilter<Long> shopBloomFilter;

    @Resource
    private CacheClient cacheClient;

    //实现互斥锁解决缓存击穿
//    @TimeCountAOP
    @Override
    public Result queryById(Long id) {
       //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
//        Shop shop =queryWithMutex(id);
//        if (shop == null) {
//            return Result.fail("店铺不存在");
//        }
        //返回

        //逻辑过期
//        Shop shop =queryWithLogicalExpire(id);

//        //使用缓存工具类解决缓存穿透
//        Shop shop = cacheClient.queryWithPassThrough(
//                CACHE_SHOP_KEY,id,Shop.class ,this::getById,
//                CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //使用缓存工具类解决缓存击穿
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY,id,Shop.class ,this::getById,
                20L, TimeUnit.SECONDS);
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }

    public Shop queryWithMutex(Long id){
        String key = CACHE_SHOP_KEY + id;


        boolean isMightContain = shopBloomFilter.contains(id);
        if (!isMightContain) {
            log.info("布隆过滤器拦截，id: {} 不存在", id);
            return null;
        }

        // 1. 从redis查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
       // log.info("从redis中根据id获取商铺细节信息，结果：{}",shopJson);
        //2.，判断是否存在
        if(StrUtil.isNotBlank(shopJson))
        {
            //3.存在返回
            return JSONUtil.toBean(shopJson,Shop.class);
        }
        if(shopJson!=null){
            return null;
        }
        //4不存在，实现缓存重建
        //4.1获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;;
        Shop shop = null;
        try {
            boolean isLock = tryLock(lockKey);
            //4.2判断是否获取成功
            if(!isLock){
                //4.3失败，休眠重试
                Thread.sleep(50);
               return queryWithMutex(id);
            }
            // ==========================================
            // 【核心修复】获取锁成功后，必须再次检测缓存是否存在！(Double Check)
            // ==========================================
            shopJson = stringRedisTemplate.opsForValue().get(key);
            // 如果缓存已经被其他线程重建好了，直接返回，不要再去查库了
            if (StrUtil.isNotBlank(shopJson)) {
                // 释放锁可以在 finally 中统一处理，或者这里直接返回也行（finally 依然会执行）
                return JSONUtil.toBean(shopJson, Shop.class);
            }
            // 如果是空值缓存
            if (shopJson != null) {
                return null;
            }
            // ==========================================

            //4.4成功查询id数据库 是重建的过程
            shop = getById(id);
            //模拟重建延迟
            Thread.sleep(200);
            if(shop==null){
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6存在，写入reids,并设置缓存的过期时间
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放互斥锁
            unLock(lockKey);
        }

        return shop;
    }
    //获取锁
    private  boolean tryLock(String key){
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

//
//    //缓存穿透处理代码封装
//    public Shop queryWithPassThrough(Long id) {
//        String key = CACHE_SHOP_KEY + id;
//
//        // ================== 布隆过滤器核心逻辑开始 ==================
//        // 2. 先查询布隆过滤器
//        // contains 返回 false，说明一定不存在，直接拦截，不需要查 Redis 和 DB
//        boolean isMightContain = shopBloomFilter.contains(id);
//        if (!isMightContain) {
//            log.info("布隆过滤器拦截，id: {} 不存在", id);
//            return null;
//        }
//        // ================== 布隆过滤器核心逻辑结束 ==================
//
//        // 注意：如果布隆过滤器返回 true，说明“可能存在”，继续走下面的 Redis -> DB 流程
//        // 这时候原来的“缓存空值”逻辑依然有用，用于处理布隆过滤器误判的那一小部分情况（回兜底）
//
//        // 1. 从redis查询商铺信息
//        String shopJson = stringRedisTemplate.opsForValue().get(key);
//        log.info("从redis中根据id获取商铺细节信息，结果：{}",shopJson);
//        //2.，判断是否存在
//        if(StrUtil.isNotBlank(shopJson))
//        {
//            //3.存在返回
//            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
//            return shop;
//        }
//        if(shopJson!=null){
//
//            return null;
//
//        }
//        //4不存在，查询数据库 使用的是mybatis-plus的查询
//        Shop shop = getById(id);
//
//        //5.库也不存在数据报错
//        if (shop == null) {
//            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
//            return  null;
//        }
//        //6存在，写入reids,并设置缓存的过期时间
//        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
//        //返回
//        return shop;
//    }
//



    @Override
    @Transactional
    public Result update(Shop shop) {
        //1.更新数据库
        updateById(shop);

        //2.删除缓存
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        String key = CACHE_SHOP_KEY + id;
        stringRedisTemplate.delete(key);


        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1.判断是否需要根据组表查询
        if(x==null||y==null){
            // 根据类型分页查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());

        }
        //2.分页参数
        int from = (current -1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current*SystemConstants.DEFAULT_PAGE_SIZE;
        //3.查询、按照距离排序、分页

        String key =SHOP_GEO_KEY+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        //4.查询redis、按照距离排序、分页、结果 shopid distance
        if(results==null){
            return Result.ok();
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        //截取从from到end
        if(list.size()<=from)return Result.ok(Collections.emptyList());
        List<Long> ids = new ArrayList<>(list.size());
        Map<String,Distance> distanceMap = new HashMap<>(list.size());

        list.stream().skip(from).forEach(result -> {
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });

        String idsStr = StrUtil.join(",",ids);
        //根据id查询shop
        List<Shop> shops = query().in("id", ids).last("order By field( id ,"+idsStr+")").list();

        for(Shop shop:shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        return Result.ok(shops);
    }


    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询数据
        Shop shop = getById(id);
        //模拟延迟
        Thread.sleep(200);
        //2.封装逻辑时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds( expireSeconds));
        //写入redis 逻辑存储
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }



    //构建线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);


    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id;

        //1.提交商铺id
        String shopJSON = stringRedisTemplate.opsForValue().get(key);
        //判断是否击中缓存
        if (StrUtil.isBlank(shopJSON)) {
          return null;
        }
        //命中，需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(shopJSON,RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        //判断是否过期
        if(redisData.getExpireTime().isAfter(LocalDateTime.now())){
            //未过期，直接返回店铺
            return shop;
        }
        //已过期需要缓存重建
        //缓存重建
        //1.获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY+id;
        boolean islock =  tryLock(lockKey);
        //2.判断是否获得锁成功
        if(islock){
            //3.成功开启独立线程实现缓存重建 线程池
            CACHE_REBUILD_EXECUTOR.execute(()->{
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }

        //4.返回过期的商铺信息
        return  shop;
    }
}
