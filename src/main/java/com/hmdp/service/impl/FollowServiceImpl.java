package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private IUserService userService;

    /**
     * 关注或取关用户
     * @param id 被关注或取关的用户id
     * @param isFollow 是否关注，true为关注，false为取关
     * @return 无
     */
    @Override
    public Result follow(Long id, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        // 查询关注关系，保证关注和取关的幂等
        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        if(isFollow){
            // 已关注，无需重复插入，避免数据库出现重复记录
            if(count > 0){
                return Result.ok();
            }
            // 关注
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(id);
            final boolean isSuccess = save(follow);
            if(isSuccess){
                // 关注成功，更新缓存
                stringRedisTemplate.opsForSet().add(key, id.toString());
            }
        } else {
            // 未关注，无需取消，避免缓存出现残留
            if(count == 0){
                return Result.ok();
            }
            // 取消关注
            final boolean isSuccess = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId).eq("follow_user_id", id));
            if(isSuccess){
                // 取消关注成功，更新缓存
                stringRedisTemplate.opsForSet().remove(key, id.toString());
            }
        }
        return Result.ok();
    }

    /**
     * 查询当前用户是否关注了指定用户
     * @param id 目标用户id
     * @return 关注状态数据
     */
    @Override
    public Result isFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        // 查询用户是否关注了该用户
        Integer count = query().eq("user_id", userId).eq("follow_user_id", id).count();
        return Result.ok(count);
    }

    /**
     * 查询当前用户与指定用户的共同关注
     * @param id 目标用户id
     * @return 共同关注的用户列表
     */
    @Override
    public Result followCommons(Long id) {
        // 1. 获取两个key：当前用户关注的用户和关注当前用户的用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;
        String key2 = "follows:" + id;
        // 2. 从缓存中获取交集
        Set<String> set = stringRedisTemplate.opsForSet().intersect(key, key2);
        if(set == null || set.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 数据转换
        List<Long> ids = set.stream().map(Long::valueOf).collect(Collectors.toList());
        // 3. 返回结果
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
