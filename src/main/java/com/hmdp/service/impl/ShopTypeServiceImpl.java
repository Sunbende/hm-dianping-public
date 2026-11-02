package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 查询所有商铺类型
     * @return 商铺类型列表
     */
    @Override
    public Result queryAll() {
        String key = RedisConstants.CACHE_SHOPT_TYPE_KEY;
        String shopTypeJson = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(shopTypeJson)) {
            List<ShopType> shopTypeList = JSONUtil.toList(shopTypeJson, ShopType.class);
            return Result.ok(shopTypeList);
        }
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        if(shopTypeList.isEmpty()) {
            // TODO 处理缓存穿透问题，缓存空值
            stringRedisTemplate.opsForValue().set(key, "[]");
        } else {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shopTypeList));
        }
        return Result.ok(shopTypeList);
    }
}
