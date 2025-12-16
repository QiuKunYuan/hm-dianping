package com.hmdp;

import com.hmdp.service.IShopService;
import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;

    @Resource
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

//    @Test
//    public void test2Redis() throws InterruptedException {
//        shopService.saveShop2Redis(1L,10L);
//    }

    @Test
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


}
