package com.hmdp.utils; // 或者放在 config 包下

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.util.List;

@Slf4j
@Component
public class BloomFilterInit implements CommandLineRunner {
    //新建一个类实现 CommandLineRunner，Spring 会在所有 Bean
    // 都创建好之后执行 run 方法，这时候调用 shopService 就非常安全了。

    @Resource
    private IShopService shopService;

    @Resource
    private RBloomFilter<Long> shopBloomFilter;

    @Override
    public void run(String... args) {
        log.info("开始预热布隆过滤器...");
        // 查询所有店铺 ID
        List<Shop> list = shopService.list();

        for (Shop shop : list) {
            shopBloomFilter.add(shop.getId());
        }
        log.info("布隆过滤器预热完成，当前包含元素数量：{}", shopBloomFilter.count());
    }
}