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
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    @Autowired
    private RedissonClient redissonClient;

    private volatile IVoucherOrderService proxy;

    private static final String STREAM_QUEUE = "stream.orders";
    private static final String CONSUMER_GROUP = "g1";
    private static final String CONSUMER_NAME = "c1";

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    /**
     * 初始化消费者线程，启动秒杀订单的异步处理
     * @return 无
     */
    @PostConstruct
    private void init() {
        // 启动消费者线程
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    /**
     * Redis Stream 消费者：异步处理秒杀订单
     */
    private class VoucherOrderHandler implements Runnable {

        /**
         * 消费者线程主逻辑：循环读取并处理 Redis Stream 中的秒杀订单消息
         * @return 无
         */
        @Override
        public void run() {
            while (true) {
                try {
                    // 1. 消费新消息：XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS stream.orders >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(STREAM_QUEUE, ReadOffset.lastConsumed())
                    );
                    // 2. 无新消息则继续等待
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    // 3. 解析消息为 VoucherOrder
                    MapRecord<String, Object, Object> record = list.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    // 4. 异步下单
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK 确认：XACK stream.orders g1 msgId
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_QUEUE, CONSUMER_GROUP, record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    // Consumer Group 尚未创建时做 lazy create
                    if (e.getMessage() != null && e.getMessage().contains("NOGROUP")) {
                        try {
                            stringRedisTemplate.opsForStream().createGroup(STREAM_QUEUE, CONSUMER_GROUP);
                            log.info("Consumer Group [{}] lazy create 成功", CONSUMER_GROUP);
                        } catch (Exception ignored) {
                            // Stream 仍不存在或 Group 已存在，忽略
                        }
                        continue;
                    }
                    handlePendingList();
                }
            }
        }

        /**
         * 消费 Pending List：重新处理未 ACK 的消息
         * @return 无
         */
        private void handlePendingList() {
            while (true) {
                try {
                    // 1. 读取 pending list：XREADGROUP GROUP g1 c1 COUNT 1 STREAMS stream.orders 0
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                            StreamReadOptions.empty().count(1),
                            StreamOffset.create(STREAM_QUEUE, ReadOffset.from("0"))
                    );
                    // 2. pending list 为空则结束
                    if (list == null || list.isEmpty()) {
                        break;
                    }
                    // 3. 解析消息
                    MapRecord<String, Object, Object> record = list.get(0);
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(record.getValue(), new VoucherOrder(), true);
                    // 4. 重试下单
                    handleVoucherOrder(voucherOrder);
                    // 5. ACK 确认
                    stringRedisTemplate.opsForStream().acknowledge(STREAM_QUEUE, CONSUMER_GROUP, record.getId());
                } catch (Exception e) {
                    log.error("处理 pending list 异常", e);
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        /**
         * 执行下单逻辑（分布式锁 + 代理调用事务方法）
         * @param voucherOrder 订单信息
         * @return 无
         */
        private void handleVoucherOrder(VoucherOrder voucherOrder) {
            // 1. 获取用户
            Long userId = voucherOrder.getUserId();
            // 2. 创建锁对象
            RLock redisLock = redissonClient.getLock("lock:order:" + userId);
            // 3. 尝试获取锁
            boolean isLock = redisLock.tryLock();
            // 4. 判断是否获得锁
            if (!isLock) {
                log.error("不允许重复下单！");
                return;
            }
            try {
                // 注意：Spring 事务绑定在 ThreadLocal 中，多线程环境下需通过代理对象调用事务方法
                proxy.createVoucherOrder(voucherOrder);
            } finally {
                // 释放锁
                redisLock.unlock();
            }
        }
    }

    /**
     * 秒杀下单（同步入口，异步处理）
     * @param voucherId 优惠券id
     * @return 订单id
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 获取代理对象（必须在执行 Lua 脚本前完成，否则消费者线程可能先读到消息导致 proxy 为 null）
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 2. 获取用户id
        Long userId = UserHolder.getUser().getId();
        // 3. 生成订单id
        long orderId = redisIdWorker.nextId("order");
        // 4. 执行 Lua 脚本：校验库存 & 一人一单 & 发送 Stream 消息
        final Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );
        // 5. 判断是否有购买资格
        int flag = result.intValue();
        if (flag != 0) {
            return Result.fail(flag == 1 ? "库存不足" : "不能重复下单");
        }
        // 6. 返回订单id（实际下单由消费者线程异步完成）
        return Result.ok(orderId);
    }

    /**
     * 真正执行下单（事务方法，由消费者线程通过代理调用）
     * @param voucherOrder 订单信息
     * @return 无
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 1. 一人一单校验
        Long userId = voucherOrder.getUserId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0) {
            log.error("该用户已下单过");
            return;
        }

        // 2. 扣减库存（乐观锁）
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherOrder.getVoucherId())
                .gt("stock", 0)
                .update();
        if (!success) {
            log.error("扣减库存失败");
            return;
        }

        // 3. 保存订单
        save(voucherOrder);
    }
}
