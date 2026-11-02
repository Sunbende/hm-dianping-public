package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/follow")
public class FollowController {

    @Autowired
    private IFollowService followService;

    /**
     * 关注或取消关注用户
     * @param id 目标用户id
     * @param isFollow 是否关注，true表示关注，false表示取消关注
     * @return 无
     */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable Long id, @PathVariable Boolean isFollow) {
        return followService.follow(id, isFollow);
    }

    /**
     * 判断是否已关注用户
     * @param id 目标用户id
     * @return 是否已关注
     */
    @GetMapping("/or/not/{id}")
    public Result follow(@PathVariable Long id) {
        return followService.isFollow(id);
    }

    /**
     * 查询当前用户与目标用户的共同关注
     * @param id 目标用户id
     * @return 共同关注的用户列表
     */
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable Long id) {
        return followService.followCommons(id);
    }
}
