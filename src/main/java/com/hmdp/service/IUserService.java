package com.hmdp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 发送短信验证码并保存验证码
     * @param phone 手机号
     * @param session 会话对象
     * @return 无
     */
    Result sendCode(String phone, HttpSession session);

    /**
     * 登录功能
     * @param loginForm 登录表单（手机号、验证码）
     * @param session 会话对象
     * @return 登录凭证token
     */
    Result login(LoginFormDTO loginForm, HttpSession session);

    /**
     * 登出功能，删除Redis中的登录token
     * @param token 请求头中的登录凭证
     * @return 无
     */
    Result logout(String token);

    /**
     * 签到统计
     * @return 签到次数
     */
    Result signCount();

    /**
     * 用户签到
     * @return
     */
    Result sign();
}
