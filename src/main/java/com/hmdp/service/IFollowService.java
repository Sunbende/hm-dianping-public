package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注或取关指定用户
     * @param id 目标用户id
     * @param isFollow 是否关注，true为关注，false为取关
     * @return 无
     */
    Result follow(Long id, Boolean isFollow);

    /**
     * 判断当前用户是否关注指定用户
     * @param id 目标用户id
     * @return 是否已关注
     */
    Result isFollow(Long id);

    /**
     * 查询当前用户与指定用户的共同关注
     * @param id 目标用户id
     * @return 共同关注的用户列表
     */
    Result followCommons(Long id);
}
