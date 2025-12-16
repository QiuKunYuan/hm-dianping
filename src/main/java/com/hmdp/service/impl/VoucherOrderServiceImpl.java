package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import lombok.val;
import org.springframework.aop.framework.AopContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {


    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedisIdWorker redisIdWorker;


    @Override

    public Result seckillVoucher(Long voucherId) {

        //1.查询优惠券
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

        Long userId = UserHolder.getUser().getId();
        //等我事务提交完然后再去释放锁
        //intern是入池
        synchronized(userId.toString().intern()) {
            //获取当前事务的代理对象 使得我们的事务生效。如果不经过代理，createVoucherOrder 上的 @Transactional 注解就会失效（
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        }
    }

    @Transactional
    //不建议synchronized添加到方法上，这样service会对每个用户都限制锁（锁是公用的） 但是我们是解决一人一单问题 只需要对一个用户加锁
    public Result createVoucherOrder(Long voucherId) {
        //6.一人一单
        Long userId = UserHolder.getUser().getId();


        //6.1查询订单
        Long count = query().eq("user_id",userId).eq("voucher_id",voucherId).count();

        if(count>0){
            return Result.fail("用户已经购买过一次了");
        }

        //5.扣减库存
        boolean success =seckillVoucherService.update().setSql("stock = stock -1")
                .eq("voucher_id", voucherId).gt("stock",0).update();

        if(!success){
            //库存不足
            return Result.fail("库存不足");
        }



        //6.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2用户id

        voucherOrder.setUserId(userId);
        //6.3代金卷id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);

    }

}
