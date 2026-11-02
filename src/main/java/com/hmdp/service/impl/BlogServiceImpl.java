package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IFollowService followService;

    /**
     * 查询笔记点赞量最高的前5个用户
     * @param id 笔记id
     * @return 点赞用户列表
     */
    @Override
    public Result queryBlogLikes(Long id) {
        String key = BLOG_LIKED_KEY + id;
        // 1.查询top5点赞的用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if(top5 == null || top5.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 2.解析用户id
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", userIds);
        // 3.查询用户
        final List<UserDTO> userDTOS = userService.query()
                .in("id", userIds).last("ORDER BY FIELD(id, " + idStr + ")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        // 4. 返回结果
        return Result.ok(userDTOS);
    }

    /**
     * 分页查询热门笔记
     * @param current 当前页码
     * @return 热门笔记列表
     */
    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    /**
     * 根据id查询笔记详情
     * @param id 笔记id
     * @return 笔记详情数据
     */
    @Override
    public Result queryBlogById(Long id) {
        // 1. 查询blog
        Blog blog = getById(id);
        if(blog == null){
            return Result.fail("笔记不存在");
               }
        // 2. 查询blog有关的用户
        queryBlogUser(blog);
        // 3. 查询blog是否被点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    /**
     * 判断当前登录用户是否点赞过该笔记，并设置到笔记对象中
     * @param blog 笔记数据
     * @return 无
     */
    private void isBlogLiked(Blog blog) {
        // 1. 获取登录用户
        UserDTO user = UserHolder.getUser();
        if(user == null){
            // 未登录直接return
            return;
        }
        Long userId = user.getId();
        // 2. 判断当前登录用户是否点赞
        String key = BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score != null);
    }

    /**
     * 点赞或取消点赞笔记
     * @param id 笔记id
     * @return 无
     */
    @Override
    public Result likeBlog(Long id) {
        // 1. 获取登录用户
        Long userId = UserHolder.getUser().getId();
        // 2. 判断当前登录用户是否点赞
        String key = BLOG_LIKED_KEY + id;
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score == null){
            // 3. 未点赞，数据库点赞数+1 保存用户到redis的sortedset集合
            final boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            if(isSuccess){ stringRedisTemplate.opsForZSet().add(key, userId.toString(), System.currentTimeMillis()); }
        } else{
            // 4. 已点赞，数据库点赞数-1 从redis的sortedset集合中移除用户id
            final boolean isSuccess = update().setSql("liked = liked - 1").eq("id", id).update();
            if(isSuccess){ stringRedisTemplate.opsForZSet().remove(key, userId.toString()); }
        }
        return Result.ok();
    }

    /**
     * 保存探店博文
     * @param blog 探店博文信息
     * @return 博文id
     */
    @Override
    public Result saveBlog(Blog blog) {
        // 1.获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 2.保存探店博文
        final boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("保存失败");
        }
        // 3.查询笔记作者的全部粉丝 select * from tb_follow where follow_user_id = user_id
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        // 4.推送笔记id给所有粉丝
        for(Follow follow : follows){
            // 4.1. 粉丝id
            Long userId = follow.getUserId();
            // 4.2. 推送笔记id给粉丝
            String key = FEED_KEY + userId;
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        // 5.返回id
        return Result.ok(blog.getId());
    }

    /**
     * 查询关注用户的博文
     * @param max 上次查询的最小id，本次查询的最大id
     * @param offset 分页偏移量
     * @return 关注用户的博文列表
     */
    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        // 1. 获取当前用户
        Long userId = UserHolder.getUser().getId();
        // 2. 查询收件箱
        String key = FEED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 2);
        if(typedTuples == null || typedTuples.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        // 3. 解析数据：blogId, minTime, offset
        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for(ZSetOperations.TypedTuple<String> typedTuple : typedTuples){
            // 3.1. 获取笔记id
            ids.add(Long.valueOf(typedTuple.getValue()));
            // 3.2. 最小时间
            long time = typedTuple.getScore().longValue();
            if(time == minTime){
                os++;
            } else {
                minTime = time;
                os = 1;
            }
        }
        // 4. 根据id查询blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id, " + idStr + ")").list();
        for(Blog blog : blogs){
            // 4.1. 查询blog有关的用户
            queryBlogUser(blog);
            // 4.2. 查询blog是否被点赞
            isBlogLiked(blog);
        }
        // 5. 封装并返回
        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(minTime);
        result.setOffset(os);
        return Result.ok(result);
    }

    /**
     * 查询笔记作者信息并设置到笔记对象中
     * @param blog 笔记数据
     * @return 无
     */
    private void queryBlogUser(Blog blog) {
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

}
