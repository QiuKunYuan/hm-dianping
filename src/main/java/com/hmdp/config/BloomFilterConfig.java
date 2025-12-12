package com.hmdp.config;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class BloomFilterConfig {

    @Resource
    private RedissonClient redissonClient;

    /**
     * 定义一个 Bean，方便在 Service 中注入使用
     */
    @Bean
    public RBloomFilter<Long> shopBloomFilter() {
        // 定义布隆过滤器的名称
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("shop:bloom:filter");

        // 初始化布隆过滤器
        // expectedInsertions: 预期插入的数据量 (例如店铺可能有 10万家)
        // falseProbability: 误判率 (通常 0.01 或 0.03)
        // 注意：tryInit 只会在 key 不存在时初始化，重启不会覆盖
        bloomFilter.tryInit(100000L, 0.01);

        return bloomFilter;
    }

}