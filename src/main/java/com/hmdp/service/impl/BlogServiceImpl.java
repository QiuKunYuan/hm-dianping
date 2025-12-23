package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

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

    @Override
    public Result queryBlogById(Long id) {
        //1.查询blog
        Blog blog =getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        //2.查询blog有关用户
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    private void isBlogLiked(Blog blog) {
        //判断当前登录用户是否已经点赞 先获取
        UserDTO user = UserHolder.getUser();
        if(user==null){
            //未登录
            return;
        }
        Long userId = user.getId();
        String key =  BLOG_LIKED_KEY+blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        blog.setIsLike(score!=null);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(
                blog -> {
                    this.queryBlogUser(blog);
                    this.isBlogLiked(blog);
                }

        );
        return Result.ok(records);

    }

    @Override
    public Result likeBlog(Long id) {
        //判断当前登录用户是否已经点赞 先获取
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        String key =  BLOG_LIKED_KEY+id;

        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());

        //没有点赞可以点赞 数据库点赞数加一 保存用户到Redis的set集合
        if(score ==null){
            boolean isSuccesss = update().setSql("liked = liked +1").eq("id", id).update();
            if(isSuccesss) {
                stringRedisTemplate.opsForZSet().add(key, userId.toString(),System.currentTimeMillis());
            }
        }else {

            //点了就可以取消 点赞减一 set里去掉
            boolean isSuccesss = update().setSql("liked = liked -1").eq("id", id).update();
            if(isSuccesss) {
                stringRedisTemplate.opsForZSet().remove(key, userId.toString());
            }
        }



        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) throws NullPointerException {
        //返回查询的用户
        String key =  BLOG_LIKED_KEY+id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0,5 );
        //解析id
        List<Long> ids  =top5.stream().map(Long::valueOf).collect(Collectors.toList());
        if(ids==null||ids.size()==0){
            return Result.ok();
        }


        String idStr = StrUtil.join(",", ids);
        //根据id找用户
        List<UserDTO> UserDTOs = userService.query().in("id",ids)
                .last("order by field(id,"+idStr+")").list()
                .stream().map(user -> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());
        //返回

        return Result.ok(UserDTOs);
    }

    @Override
    public Result queryBlogByUserId(Long id,Integer current) {
        Page<Blog> page = query().eq("user_id",id).page(new Page<>(current,SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();

        return Result.ok(records);
    }

    private void queryBlogUser(Blog blog){
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
}
