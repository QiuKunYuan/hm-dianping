package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;
import java.util.concurrent.TimeUnit;
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {


    //这个类的对象是我们手动new的 不能依赖注入

    private final StringRedisTemplate stringRedisTemplate;
    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        HandlerInterceptor.super.afterCompletion(request, response, handler, ex);

        //移除用户
        UserHolder.removeUser();
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
//        //1.获取session
//        HttpSession session = request.getSession();
//        //2.获取用户redis中用户信息
//        UserDTO user =(UserDTO) session.getAttribute("user");
//        //3.判断用户是否存在
//        if (user==null){
//            //4.不存在，拦截，返回401状态码
//            response.setStatus(401);
//            return false;
//        }
//        //5.存在，保存用户信息到ThreadLocal
//        UserHolder.saveUser(user);
//        //6.放行
//
//        return HandlerInterceptor.super.preHandle(request, response, handler);



        //1.获取请求头中1的token
        String token = request.getHeader("authorization");
        log.info("token:{}",token);
        if (StrUtil.isBlank(token)) {
            log.info("没有token");
            return true;
        }
        // 2.基于token获取redis中的用户
        String key = RedisConstants.LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);

        //3.判断用户是否存在
        if (userMap.isEmpty()){
            log.info("token无效");
            return true;
        }
        //5查询到的Hash数据转为UserDTO对象
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);

        //6.存储到ThreadLocal
        UserHolder.saveUser(userDTO);
        //7.刷新token有效期
        stringRedisTemplate.expire(key, RedisConstants.LOGIN_USER_TTL, TimeUnit.MINUTES);
        //8.放行
        return true;

    }
}
