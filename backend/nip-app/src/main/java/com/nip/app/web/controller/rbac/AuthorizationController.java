package com.nip.app.web.controller.rbac;

import cn.hutool.core.lang.Assert;
import cn.hutool.core.text.CharSequenceUtil;
import com.nip.app.api.rbac.OnlineService;
import com.nip.app.api.rbac.TokenService;
import com.nip.app.api.rbac.UserService;
import com.nip.app.config.bean.LoginProperties;
import com.nip.app.config.bean.WebSecurityProperties;
import com.nip.app.pojo.rbac.UserPo;
import com.nip.app.pojo.rbac.param.UserParam;
import com.nip.core.annotations.AnonymousAccess;
import com.nip.core.enums.ResultCodeEnum;
import com.nip.core.pojo.ResultContext;
import com.nip.core.pojo.UserOnlineInfo;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证相关 Controller
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/4 15:35 ✾
 */
@Slf4j
@RestController
@RequestMapping("/auth")
public class AuthorizationController {

    @Resource
    protected WebSecurityProperties webSecurityProperties;

    @Resource
    private LoginProperties loginProperties;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private AuthenticationManager authenticationManager;

    @Resource
    private TokenService tokenService;

    @Resource
    private OnlineService onlineService;

    @Resource
    private UserService userService;

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @AnonymousAccess
    public ResultContext<String> login(@RequestBody UserPo param, HttpServletRequest request) throws Exception {
        // 密码解密
        String password = tokenService.decrypt(param.getPassword());
        // TODO 查询、验证、清除验证码
        try {
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(param.getUserName(), password);
            Authentication authentication = authenticationManager.authenticate(authenticationToken);
            // 生成令牌
            String token = tokenService.createToken(authentication);
            // 保存在线信息
            onlineService.saveOnlineInfo(authentication, token, request);
            if (loginProperties.isSingleLogin()) {
                // 确保单点登录
                onlineService.ensureSingleOnline(authentication, token);
            }
            return ResultContext.success(token);
        } catch (Exception e) {
            log.error("认证失败", e);
            return ResultContext.fail(ResultCodeEnum.FAIL_AUTH);
        }
    }

    @PostMapping("/logout")
    @AnonymousAccess
    public ResultContext<Void> logout(HttpServletRequest request) {
        boolean delete = redissonClient.getBucket(webSecurityProperties.getOnlineKey() + tokenService.getToken(request)).delete();
        return delete ? ResultContext.success() : ResultContext.fail("登出失败");
    }

    @PostMapping("/onlineInfo")
    @AnonymousAccess
    public ResultContext<UserOnlineInfo> getOnlineInfo(HttpServletRequest request) {
        UserOnlineInfo userOnlineInfo = onlineService.getOnlineInfo(tokenService.getToken(request));
        userOnlineInfo.setPassword(CharSequenceUtil.EMPTY);
        userOnlineInfo.setAvatar("https://gw.alipayobjects.com/zos/antfincdn/XAosXuNZyF/BiazfanxmamNRoxxVxka.png");
        return ResultContext.success(userOnlineInfo);
    }

    @PostMapping("/changePassword")
    @AnonymousAccess
    public ResultContext<Boolean> changePassword(HttpServletRequest request, @RequestBody UserParam param) {
        Assert.notBlank(param.getUserName(), "用户名不能为空");
        Assert.notBlank(param.getOldPassword(), "旧密码不能为空");
        Assert.notBlank(param.getNewPassword(), "新密码不能为空");
        try {
            UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(param.getUserName(), tokenService.decrypt(param.getOldPassword()));
            Authentication authentication = authenticationManager.authenticate(authenticationToken);
            if (!authentication.isAuthenticated()) {
                return ResultContext.fail("旧密码错误");
            }
            // 让当前用户登录信息失效
            boolean delete = redissonClient.getBucket(webSecurityProperties.getOnlineKey() + tokenService.getToken(request)).delete();
            if (!delete) {
                return ResultContext.fail("登出失败");
            }
            param.setNewPassword(tokenService.decrypt(param.getNewPassword()));
        } catch (Exception e) {
            log.error("验证旧密码异常", e);
            return ResultContext.fail("验证旧密码异常");
        }
        return ResultContext.wrap(param, userService::changePassword);
    }

    @PostMapping("/getPubKey")
    @AnonymousAccess
    public ResultContext<String> getPubKey() {
        return ResultContext.wrap(() -> onlineService.getPublicKey());
    }
}
