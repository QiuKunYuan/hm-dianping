package com.hmdp;

import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private ExecutorService es = Executors.newFixedThreadPool(500);

//    @Test
//    public void test2Redis() throws InterruptedException {
//        shopService.saveShop2Redis(1L,10L);
//    }

    //@Test
    void testIdWorker() throws InterruptedException {
        //主线程如何知道所有异步执行的子线程任务什么时候全部结束？ 这就是 CountDownLatch
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = ()->{
            for(int i=0;i<100;i++){
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " +id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for(int i=0 ;i<300 ; i++){
            es.execute(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = "+(end-begin));

    }

    @Test
    void loadShopData(){
        List<Shop> list = shopService.list();
        Map<Long,List<Shop>> map =list.stream().collect(
                Collectors.groupingBy(Shop::getTypeId)
        );
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            Long typeId  = entry.getKey();
            String key ="shop:geo:"+typeId;

            List<Shop> shops = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(shops.size());
            for(Shop shop : shops){
               // stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
                locations.add(new RedisGeoCommands.GeoLocation<>(shop.getId().toString(),new Point(shop.getX(),shop.getY())));
            }
            //批量写入
            stringRedisTemplate.opsForGeo().add(key,locations);
        }
    }


}
