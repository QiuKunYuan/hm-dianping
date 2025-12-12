package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.aop.TimeCountAOP;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOPTYPE_TTL;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @TimeCountAOP
    @Override
    public Result getList() {

        // 1. 从 Redis 查询所有缓存值
        List<Object> valueList = stringRedisTemplate.opsForHash().values(CACHE_SHOPTYPE_KEY);

// 2. 如果缓存中有数据
        if (!valueList.isEmpty()) {
            // 【关键步骤】：反序列化 + 排序
            List<ShopType> typeList = valueList.stream()
                    .map(json -> JSONUtil.toBean((String) json, ShopType.class)) // 将 JSON 字符串转为 ShopType 对象
                    .sorted(Comparator.comparingInt(ShopType::getSort))           // 按照 sort 字段升序排序
                    .collect(Collectors.toList());

            log.info("从redis中获取商铺类型数据并处理完成");
            return Result.ok(typeList);
        }

// 3. 缓存未命中，查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        log.info("从数据库拿的数据,{}", shopTypeList);

        if (shopTypeList.isEmpty()) {
            return Result.fail("商铺类型不存在");
        }

// 4. 写回 Redis (转为 Map<String, String>)
        Map<String, String> map = shopTypeList.stream()
                .collect(Collectors.toMap(
                        type -> type.getId().toString(),
                        type -> JSONUtil.toJsonStr(type)
                ));

        stringRedisTemplate.opsForHash().putAll(CACHE_SHOPTYPE_KEY, map);
        stringRedisTemplate.expire(CACHE_SHOPTYPE_KEY, CACHE_SHOPTYPE_TTL, TimeUnit.MINUTES);

// 5. 返回数据库查询结果
        return Result.ok(shopTypeList);
    }
}