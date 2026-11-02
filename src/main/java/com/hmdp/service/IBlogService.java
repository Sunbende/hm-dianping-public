package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 查询笔记点赞用户列表
     * @param id 笔记id
     * @return 点赞用户列表（点赞最早的top5）
     */
    Result queryBlogLikes(Long id);

    /**
     * 分页查询热门笔记
     * @param current 当前页码
     * @return 热门笔记分页数据
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据id查询笔记详情
     * @param id 笔记id
     * @return 笔记详情数据
     */
    Result queryBlogById(Long id);

    /**
     * 点赞或取消点赞笔记
     * @param id 笔记id
     * @return 无
     */
    Result likeBlog(Long id);

    /**
     * 保存探店博文
     * @param blog 探店博文信息
     * @return 博文id
     */
    Result saveBlog(Blog blog);

    /**
     * 查询关注用户的博文
     * @param max 上次查询的最小id，本次查询的最大id
     * @param offset 分页偏移量
     * @return 关注用户的博文列表
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
