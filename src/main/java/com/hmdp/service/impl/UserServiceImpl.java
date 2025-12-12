package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result sendCode(String phone, HttpSession session) {

        //1.校验手机号
        if( RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合返回错误信息
            return Result.fail("手机号格式错误");
        }
        //3.生成验证码
        String code = RandomUtil.randomNumbers(6);
//
//        //4.保存验证码到session
//        session.setAttribute("code",code);

        //4.保存验证码到redis
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);

        //5.发送验证码

        log.debug("发送成功，验证码："+ code);
        //6.返回ok

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {

        String phone = loginForm.getPhone();
        //1.校验手机号
        if( RegexUtils.isPhoneInvalid(phone)){
            //2.如果不符合返回错误信息
            return Result.fail("手机号格式错误");
        }
//        //2.校验验证码
//        Object cacheCode = session.getAttribute("code");
//        String code = loginForm.getCode();
        //2.校验验证码 从redis获取
        String code = loginForm.getCode();
        String cacheCode = stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);


        if(cacheCode == null || !cacheCode.equals(code)){
            //3.不一致报错
            return Result.fail("验证码错误");
        }


        //4.一致根据手机号用户 使用mybatis-plus查询用户
         User user = query().eq("phone", phone).one();//eq就是equal

        //5.判断用户是否存在

        if(user ==null){
            //6.不存在创建新用户
           user= createUserWithPhone(phone);
        }

//        //7.保存用户信息到session
//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));
        //7.保存用户信息到redis

        //7.1随机生成token，作为登录令牌
        String token = UUID.randomUUID().toString();
        //7.2将UserDTO转为HashMap存储
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //将user转化为map存储
        Map<String,Object> userMap =BeanUtil.beanToMap(userDTO,new HashMap<>(), CopyOptions.create().setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName,fieldValue)->fieldValue.toString()));

        //7.3存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll( tokenKey,userMap);
        //7.4设置token有效期
        stringRedisTemplate.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);
        //8.返回token
        return Result.ok(token);



    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+ RandomUtil.randomString(10));
        //2.保存用户
        save(user);

        return user;
    }
}
