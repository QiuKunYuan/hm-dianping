package com.hmdp.config;

import com.hmdp.utils.LoginInterceptor;
import com.hmdp.utils.RefreshTokenInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class MvcConfig implements WebMvcConfigurer {


    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        //登录浏览器
        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/user/code",  //发送短信验证码
                        "/user/login",//登录
                        "/shop/**", //商铺
                        "/blog/hot",
                        "/shop-type/**",
                        "/upload/**", //上传下载
                        "/voucher/**" //优惠券

                ).addPathPatterns("/**").order(1); //拦截所有请求
        //token刷新拦截器
        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate)).order(0);//先执行
    }

}
