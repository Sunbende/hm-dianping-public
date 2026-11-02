package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisIdWorker {

    private static final long BEGIN_TIMESTAMP = 1790263320L;
    private static final long COUNT_BIT = 32L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    public long nextId(String keyprefix) {
        // 1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long timestamp = now.toEpochSecond(ZoneOffset.UTC) - BEGIN_TIMESTAMP;
        // 2. 生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        long count = stringRedisTemplate.opsForValue().increment("icr:" + keyprefix + ":" + date);

        // 3. 拼接并返回
        return timestamp << COUNT_BIT | count;
    }
}
