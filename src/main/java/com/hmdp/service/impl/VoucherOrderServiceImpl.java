package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    //在config中使用@Bean将一个方法的返回值丢给IOC容器了 因此这里可以自动注入
    @Resource
    private RedissonClient redissonClient;




    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    private  IVoucherOrderService proxy;

    @PostConstruct//当前类初始化完就会执行
    private  void init(){
        // 1. 初始化消费者组（防止 NOGROUP 错误）
        initConsumerGroup();
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private void initConsumerGroup() {
        String queueName = "stream.orders";
        String groupName = "g1";
        try {
            // 尝试创建消费者组
            // 对应命令：XGROUP CREATE stream.orders g1 0 MKSTREAM
            stringRedisTemplate.opsForStream().createGroup(queueName, ReadOffset.from("0"), groupName);
        } catch (Exception e) {
            // 如果组已经存在，会抛异常，这里直接忽略即可
            log.info("消费者组已存在或创建失败: {}", e.getMessage());
        }
    }

//    private class VoucherOrderHandler implements Runnable{
//        @Override
//        public void run() {
//            while(true){
//
//                try {
//                    //获取队列订单信息
//                    VoucherOrder voucherOrder = voucherOrderQueue.take();
//                    //创建订单
//                    handleVoucherOrder(voucherOrder);
//                } catch (Exception e) {
//                    log.error("处理订单异常",e);
//                }
//            }
//        }
//    }


    private class VoucherOrderHandler implements Runnable{

       String queueName = "stream.orders";
        @Override
        public void run() {
            while(!Thread.currentThread().isInterrupted()){

                try {
                    //获取队列订单信息 xreadgroup group g1 c1 count 1 block 2000 streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if(list ==null || list.isEmpty()){
                        continue;
                    }
                    //解析取出来的消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //创建订单
                    handleVoucherOrder(voucherOrder);

                    //ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    // 2. 关键：判断是否是容器关闭导致的异常
                    if (e.getMessage().contains("Redisson is shutdown") ||
                            Thread.currentThread().isInterrupted()) {
                        log.info("检测到服务关闭，异步下单线程安全退出");
                        break; // 跳出死循环，结束 run 方法
                    }

                    log.error("处理订单异常", e);
                    // 只有非关闭异常才处理 pending-list
                    handlePendingList();
                }
            }
        }

        private void handlePendingList() {

            while(!Thread.currentThread().isInterrupted()){

                try {
                    //获取pendinglist xreadgroup group g1 c1 count 1 streams streams.order 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if(list ==null || list.isEmpty()){
                        //获取失败说明没了 被处理过了 结束循环
                        break;
                    }
                    //解析取出来的消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    //创建订单
                    handleVoucherOrder(voucherOrder);

                    //ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",record.getId());

                } catch (Exception e) {
                    // 3. pending-list 也要做同样判断
                    if (e.getMessage().contains("Redisson is shutdown")) {
                        return;
                    }
                    log.error("处理pending-list异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt(); // 重新标记中断
                    }

                }
            }
        }
    }

    private void  handleVoucherOrder(VoucherOrder voucherOrder){
        Long userId = voucherOrder.getId();
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean isLock = lock.tryLock();

        //判断是否获取锁成功
        if(!isLock){
            log.error("不允许重复下单");

        }
        //获取当前事务的代理对象 使得我们的事务生效。如果不经过代理，createVoucherOrder 上的 @Transactional 注解就会失效（
            try{
            proxy.createVoucherOrder( voucherOrder);
            }
                finally{
                //释放锁
                lock.unlock();

            }
    }

    @PreDestroy
    private void destroy() {
        // 关闭线程池
        SECKILL_ORDER_EXECUTOR.shutdown();
        log.info("秒杀订单线程池已关闭");
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;//Lua脚本配置
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }



    @Override
//使用redisstream去将判断一人一单扔stream类型消息队列中
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();

        long orderId = redisIdWorker.nextId("order");
        //1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
                );

        int r = result.intValue();
        //判断结果是否为0
        //不为0 买不了
        if(r!=0)
        {
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }

        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();

        //返回订单id

        return Result.ok(0);




       /* //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if(voucher.getBeginTime().isAfter(LocalDateTime.now()))
        {
            //尚未开始
            return Result.fail("秒杀没开始");

        }
        //3.秒杀结束
        if(voucher.getEndTime().isBefore(LocalDateTime.now()))
        {

            return Result.fail("秒杀已经结束");

        }
        //4.判断库存是否充足
        if (voucher.getStock()<1) {
            //库存不足
            return Result.fail("库存不足");
        }

        Long userId = UserHolder.getUser().getId();*/
        //等我事务提交完然后再去释放锁
        //intern是入池
//        synchronized(userId.toString().intern()) {
//            //获取当前事务的代理对象 使得我们的事务生效。如果不经过代理，createVoucherOrder 上的 @Transactional 注解就会失效（
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        }

        //尝试获取锁对象 使用自定义简单锁
//        SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);

        //使用Redisson去创建可重写锁

        /*RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean isLock = lock.tryLock();

        //判断是否获取锁成功
        if(!isLock){
            //失败 返回错误信息 或者重试
            return Result.fail("一个人只允许一单");

        }
        //获取当前事务的代理对象 使得我们的事务生效。如果不经过代理，createVoucherOrder 上的 @Transactional 注解就会失效（
            try{
                IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
            }
                finally{
                //释放锁
                lock.unlock();

            }*/

    }

    @Transactional
    //不建议synchronized添加到方法上，这样service会对每个用户都限制锁（锁是公用的） 但是我们是解决一人一单问题 只需要对一个用户加锁
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //6.一人一单
        Long userId = voucherOrder.getUserId();


        //6.1查询订单
        Long count = query().eq("user_id",userId).eq("voucher_id", voucherOrder.getVoucherId()).count();

        if(count>0){
            log.error("用户已经购买过一次了");
        }

        //5.扣减库存 使用乐观锁CAS
        boolean success =seckillVoucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucherOrder.getVoucherId()).gt("stock",0).update();

        if(!success){
            //库存不足
            log.error("库存不足");
        }

        save(voucherOrder);

    }

}
