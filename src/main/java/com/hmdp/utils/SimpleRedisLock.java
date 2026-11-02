package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.TimeUnit;


public class SimpleRedisLock implements ILock {

    private String name;
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";

    @Override
    public boolean tryLock(long timeoutSec) {
        // 拼接线程id
        String threadId = ID_PREFIX + Thread.currentThread().getId();

        // 获取锁
        return Boolean.TRUE.equals(stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeoutSec, TimeUnit.SECONDS));
    }

//    @Override
//    public void unlock() {
//        // 获取线程提示
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        // 获取锁中的id
//        String lockId = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
//
//        // 判断是否是当前线程
//        if(!threadId.equals(lockId)) { return; }
//
//        // 释放锁
//        stringRedisTemplate.delete(KEY_PREFIX + name);
//    }

    @Override
    public void unlock() {
        // 调用lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }

}
