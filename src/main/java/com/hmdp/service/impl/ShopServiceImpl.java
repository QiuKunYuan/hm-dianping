package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.aop.TimeCountAOP;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    @TimeCountAOP
    @Override
    public Result queryById(Long id) {
        String key = CACHE_SHOP_KEY + id;
        // 1. 从redis查询商铺信息
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        log.info("从redis中根据id获取商铺细节信息，结果：{}",shopJson);
        //2.，判断是否存在
        if(StrUtil.isNotBlank(shopJson))
        {
            //3.存在返回
            Shop shop = JSONUtil.toBean(shopJson,Shop.class);
            return Result.ok(shop);
        }
        if(shopJson!=null){

            return Result.fail("店铺不存在");

        }
        //4不存在，查询数据库 使用的是mybatis-plus的查询
        Shop shop = getById(id);

        //5.库也不存在数据报错
        if (shop == null) {
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return  Result.fail("店铺不存在");
        }
        //6存在，写入reids,并设置缓存的过期时间
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回
        return Result.ok(shop);
    }

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
}
