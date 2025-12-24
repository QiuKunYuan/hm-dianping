package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.ScrollResult;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

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

    @Resource
    private FollowServiceImpl followService;


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

    @Override
    public Result saveBlog(Blog blog) {

        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean isSuccess = save(blog);
        if(!isSuccess){
            return Result.fail("Blog保存失败");
        }
        // 查询粉丝 select*from tb_follow where follow_user_id =?
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();

        //推送给粉丝
        for(Follow follow : follows){
            Long userId = follow.getUserId();
            String key  = FEED_KEY+userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());

        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1. 获得当前用户，找到我的收件箱 feed：id
        Long userId  = UserHolder.getUser().getId();
        String  key  =FEED_KEY+userId;
        //2.查询收件箱 滚动分页查询zrevrangebyscore
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 2);

        if(typedTuples ==null ||  typedTuples.isEmpty()){
            return Result.ok();
        }
        //3.解析数据：blogId、minTime（时间戳）、offset（根最小值一一样元素的个数）
        List<Long> ids  = new ArrayList<>(typedTuples.size());
        long minTime =0;
        int os =1;
        try {
            for(ZSetOperations.TypedTuple<String> tuple : typedTuples){
                if(tuple.getValue()!=null){
                    ids.add(Long.valueOf(tuple.getValue()));
                }
                long time = tuple.getScore().longValue();
                if(time ==minTime){
                    os++;
                }else {
                    minTime = time;
                    os=1;
                }
            }
        } catch (NumberFormatException e) {
            throw new RuntimeException(e);
        }
        //4.根据id查询blog
        String idStr = StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id",ids).last("order by field(id,"+idStr+")").list();

        for(Blog blog : blogs){
            User user = userService.getById(blog.getUserId());
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
        //5.封装返回
        ScrollResult r =new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    private void queryBlogUser(Blog blog){
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
}
