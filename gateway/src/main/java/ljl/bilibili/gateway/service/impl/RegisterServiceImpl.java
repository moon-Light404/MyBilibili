package ljl.bilibili.gateway.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import ljl.bilibili.client.notice.SendNoticeClient;
import ljl.bilibili.entity.user_center.user_info.User;
import ljl.bilibili.entity.user_center.user_info.Privilege;
import ljl.bilibili.gateway.constant.Constant;
import ljl.bilibili.gateway.service.RegisterService;
import ljl.bilibili.gateway.vo.request.PasswordRegisterRequest;
import ljl.bilibili.mapper.user_center.user_info.PrivilegeMapper;
import ljl.bilibili.mapper.user_center.user_info.UserMapper;
import ljl.bilibili.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
@Slf4j
@Service
public class RegisterServiceImpl implements RegisterService {
    @Resource
    UserMapper userMapper;
    @Resource
    PrivilegeMapper privilegeMapper;
    @Resource
    SendNoticeClient client;
    @Resource
    private PasswordEncoder passwordEncoder;
    /**
     *账号密码注册，并发送新增用户的数据同步消息
     */
    /**
     * 账号密码注册处理（事务性操作）
     * 1. 验证用户名唯一性，若已存在则返回错误；
     * 2. 若不存在，将请求参数转换为用户实体，对密码进行加密处理后插入数据库；
     * 3. 构建用户新增数据同步消息，移除敏感字段后发送通知；
     * 4. 为新用户初始化权限记录；
     * 5. 返回新用户ID
     */
    @Transactional  // 声明事务：确保用户插入、权限插入、数据同步通知发送的原子性
    @Override
    public Result<Integer> passwordRegister(PasswordRegisterRequest passwordRegisterRequest){
        // 构建查询条件：根据用户名查询是否已存在该用户
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUserName, passwordRegisterRequest.getUserName());

        // 用户名唯一性校验
        if (userMapper.selectOne(wrapper) != null) {
            return Result.error("该用户已存在");  // 用户名已存在，返回错误结果
        } else {
            // 为新用户初始化权限记录（关联用户ID，建立用户-权限关系）

            // 构建数据同步消息：将用户实体转换为Map并补充元数据
            // 插入新用户记录到数据库
            // 将注册请求对象转换为用户实体
            User user = passwordRegisterRequest.toEntity();
            // 密码加密处理（使用Spring Security的PasswordEncoder，不可逆加密）
            user.setPassword(passwordEncoder.encode(user.getPassword()));
            userMapper.insert(user);
//            CompletableFuture<Void> sendDBChangeNotice = CompletableFuture.runAsync(() -> {
            ObjectMapper objectMapper = new ObjectMapper();
            Map<String, Object> map = objectMapper.convertValue(user, Map.class);
            // 同步的数据表名为user
            map.put(Constant.TABLE_NAME, "user");
            // 指定操作类型为add
            map.put(Constant.OPERATION_TYPE, Constant.OPERATION_TYPE_ADD);
            // 移除敏感/冗余字段：从Map中删除无需同步的字段
            map.remove(Constant.TABLE_IGNORE_USERNAME);
            map.remove(Constant.TABLE_IGNORE_PASSWORD);
            map.remove(Constant.TABLE_IGNORE_PHONE_NUMBER);
            map.remove(Constant.TABLE_IGNORE_MAIL_NUMBER);
            // 调用Feign客户端发送通知
            client.sendDBChangeNotice(map);
//            });
            // 创建用户权限记录：
            privilegeMapper.insert(new Privilege().setUserId(user.getId()));
            return Result.data(user.getId());
        }

    }
}
