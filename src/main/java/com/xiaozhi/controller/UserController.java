package com.xiaozhi.controller;

import java.util.Map;

import javax.annotation.Resource;

import com.xiaozhi.common.exception.UserPasswordNotMatchException;
import com.xiaozhi.common.exception.UsernameNotFoundException;
import com.xiaozhi.common.web.AjaxResult;
import com.xiaozhi.common.web.SessionProvider;
import com.xiaozhi.entity.SysUser;
import com.xiaozhi.security.AuthenticationService;
import com.xiaozhi.service.SysUserService;
import com.xiaozhi.utils.CmsUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 用户信息
 * 
 * @author: Joey
 * 
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    @Resource
    private SysUserService userService;

    @Resource
    private AuthenticationService authenticationService;

    @Resource
    private SessionProvider sessionProvider;

    /**
     * @param username
     * @param password
     * @return
     * @throws UsernameNotFoundException
     * @throws UserPasswordNotMatchException
     */
    @PostMapping("/login")
    public Mono<AjaxResult> login(@RequestBody Map<String, Object> loginRequest, ServerWebExchange exchange) {
        try {
            String username = (String) loginRequest.get("username");
            String password = (String) loginRequest.get("password");

            userService.login(username, password);
            SysUser user = userService.query(username);

            // 保存用户
            CmsUtils.setUser(exchange, user);

            return sessionProvider.setAttribute(exchange, SysUserService.USER_SESSIONKEY, user)
                    .thenReturn(AjaxResult.success(user));
        } catch (UsernameNotFoundException e) {
            return Mono.just(AjaxResult.error("用户不存在"));
        } catch (UserPasswordNotMatchException e) {
            return Mono.just(AjaxResult.error("密码错误"));
        } catch (Exception e) {
            log.info(e.getMessage(), e);
            return Mono.just(AjaxResult.error("操作失败"));
        }
    }

    /**
     * 用户信息查询
     * 
     * @param username
     * @return
     */
    @GetMapping("/query")
    public Mono<AjaxResult> query(String username) {
        try {
            SysUser user = userService.query(username);
            AjaxResult result = AjaxResult.success();
            result.put("data", user);
            return Mono.just(result);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Mono.just(AjaxResult.error());
        }
    }

    /**
     * 用户信息修改
     *
     * @param user
     * @return
     */
    @PostMapping("/update")
    public Mono<AjaxResult> update(SysUser user, ServerWebExchange exchange) {
        try {
            SysUser userQuery = userService.query(user.getUsername());
            if (ObjectUtils.isEmpty(userQuery)) {
                return Mono.just(AjaxResult.error("无此用户，操作失败"));
            }
            if (!StringUtils.hasText(user.getPassword())) {
                String newPassword = authenticationService.encryptPassword(user.getPassword());
                user.setPassword(newPassword);
            }

            if (0 < userService.update(user)) {
                return Mono.just(AjaxResult.success(user));
            }
            return Mono.just(AjaxResult.error());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
            return Mono.just(AjaxResult.error());
        }
    }
}