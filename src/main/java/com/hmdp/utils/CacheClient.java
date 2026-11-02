package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Configuration
@Slf4j
public class CacheClient {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public void set(String key, Object value, Long time, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        // 设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        // 写入redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // 解决缓存穿透问题
    public <T, ID> T queryWithPassThrough(String keyPrefix, ID id, Class<T> clazz, Function<ID, T> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, clazz);
        }
        // 若不为空，则一定是空字符串
        if(json != null) {
            return null;
        }

        T t = dbFallback.apply(id);
        if(t == null) {
            // 将空值写入redis，解决缓存穿透问题
            stringRedisTemplate.opsForValue().set(key, "", time, unit);
            return null;
        }
        // 写入redis
        this.set(key, t, time, unit);
        return t;
    }

    // 解决缓存击穿问题（通过逻辑过期）
    public <T, ID> T queryWithLogicalExpire(String keyPrefix, ID id,  Class<T> clazz, Function<ID, T> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 1. 从Redis中查询店铺缓存
        String json = stringRedisTemplate.opsForValue().get(key);
        // 2. 判断是否存在
        if (StrUtil.isBlank(json)) {
            return null;  // 不存在直接返回null
        }

        // 3. 缓存命中，判断是否过期
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        T t = JSONUtil.toBean((JSONObject) redisData.getData(), clazz);

        // 4. 判断是否过期
        if(expireTime.isAfter(LocalDateTime.now())) {
            return t;   // 4.1 若未过期，直接返回商品信息
        }

        // 4.2 若过期，获取互斥锁，并判断是否成功
        String lockKey = keyPrefix + id;
        boolean isLock = tryLock(lockKey);
        if(!isLock) {
            // 4.2.1 若失败，返回旧数据
            return t;
        } else {
            // 4.2.2 若成功，原线程开启独立线程更新缓存，且原线程返回旧数据
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    // 查询数据库
                    final T r1 = dbFallback.apply(id);
                    // 重建缓存
                    setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 释放锁
                    unlock(lockKey);
                }

            });
        }
        return t;
    }

    // 尝试获取互斥锁
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 释放互斥锁
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
