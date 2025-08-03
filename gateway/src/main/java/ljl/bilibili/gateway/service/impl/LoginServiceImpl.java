package ljl.bilibili.gateway.service.impl;
import com.aliyuncs.exceptions.ClientException;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import ljl.bilibili.client.notice.SendNoticeClient;
import ljl.bilibili.entity.user_center.user_info.User;
import ljl.bilibili.entity.user_center.user_info.Privilege;
import ljl.bilibili.gateway.service.LoginService;
import ljl.bilibili.gateway.util.CaptchaUtil;
import ljl.bilibili.gateway.util.JwtUtil;
import ljl.bilibili.gateway.util.SendMessage;
import ljl.bilibili.gateway.vo.request.MailLoginRequest;
import ljl.bilibili.gateway.vo.request.PasswordLoginRequest;
import ljl.bilibili.gateway.vo.request.PhoneNumberLoginRequest;
import ljl.bilibili.mapper.user_center.user_info.PrivilegeMapper;
import ljl.bilibili.mapper.user_center.user_info.UserMapper;
import ljl.bilibili.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpCookie;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;

import javax.annotation.Resource;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

import static ljl.bilibili.gateway.constant.Constant.*;

/**
 *登录
 */
@Service
@Slf4j
public class LoginServiceImpl implements LoginService {
    @Resource
    UserMapper userMapper;

    @Resource
    RedisTemplate redisTemplate;
    @Resource
    PrivilegeMapper privilegeMapper;
    @Resource
    SendNoticeClient client;
    @Resource
    private PasswordEncoder passwordEncoder;
    @Value("${spring.mail.username}")
    private String from;

    @Resource
    public JavaMailSender mailSender;
    /**
     *解密数据库中加密后密码验证用户，登录成功返回长短token与用户id
     **/
    /**
     * 密码登录验证与令牌生成
     * 该方法通过用户名查询用户信息，验证密码加密匹配性及图形验证码有效性，
     * 验证通过后生成并返回用户ID、短期访问令牌和长期刷新令牌
     */
    @Override
    public Map<String, Object> passwordLogin(PasswordLoginRequest passwordLoginRequest, String sessionId) {
        // 从Redis获取当前会话对应的图形验证码
        String code = (String) redisTemplate.opsForValue().get(sessionId);
        // 构建查询条件：根据用户名查询用户信息
        LambdaQueryWrapper<User> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(User::getUserName, passwordLoginRequest.getUserName());
        User user = userMapper.selectOne(wrapper);
        Map<String, Object> map = new HashMap<>(2);
        // 如果用户存在且密码匹配、验证码一致，则登录成功并生成令牌
        if (user != null && passwordEncoder.matches(passwordLoginRequest.getPassword(), user.getPassword()) && code.equals(passwordLoginRequest.getCaptcha())) {
            map.put(USERIDENTITY, user.getId());          // 存储用户唯一标识
            map.put(SHORT_TOKEN, JwtUtil.generateShortToken(user.getId()));  // 生成短期访问令牌
            map.put(LONG_TOKEN, JwtUtil.generateLongToken(user.getId()));    // 生成长期刷新令牌
        }
        return map;
    }

    @Override
    @Transactional
    /**
     *手机号登录，若无该用户则新创建一个用户并发送新增用户数据同步消息
     **/
    public Map<String, Object> phoneNumberLogin(PhoneNumberLoginRequest phoneNumberLoginRequest) {
        String code = (String) redisTemplate.opsForValue().get(phoneNumberLoginRequest.getPhoneNumber());
        LambdaQueryWrapper<User> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(User::getPhoneNumber, phoneNumberLoginRequest.getPhoneNumber());
        User user = userMapper.selectOne(wrapper);
        Integer id=null;
        Map<String, Object> map = new HashMap<>(2);
        //如果用户存在且手机接收的验证码和传来的验证码对上了就返回userId和长短token
        if (user != null && passwordEncoder.matches(phoneNumberLoginRequest.getPhoneNumber(),user.getPhoneNumber()) &&code.equals(phoneNumberLoginRequest.getCaptcha()) ) {
            map.put(USERIDENTITY, user.getId());
        }
        //不存在则直接创建一个新用户
        if(user ==null){
            User newUser =phoneNumberLoginRequest.toEntity().setCover("https://labilibili.com/user-cover/default.png");
            passwordEncoder.encode(newUser.getPassword());
            userMapper.insert(newUser);
            privilegeMapper.insert(new Privilege().setUserId(newUser.getId()));
            id= newUser.getId();
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Object> userMap = objectMapper.convertValue(newUser, Map.class);
                userMap.put(TABLE_NAME, USER_TABLE_NAME);
                userMap.put(OPERATION_TYPE, OPERATION_TYPE_ADD);
                userMap.remove(TABLE_IGNORE_USERNAME);
                userMap.remove(TABLE_IGNORE_PASSWORD);
                userMap.remove(TABLE_IGNORE_PHONE_NUMBER);
                client.sendDBChangeNotice(userMap);
            map.put(USERIDENTITY, newUser.getId());
        }
        map.put(SHORT_TOKEN, JwtUtil.generateShortToken(id));
        map.put(LONG_TOKEN, JwtUtil.generateLongToken(id));
        return map;
    }
    /**
     *邮箱号登录，若无该用户则新创建一个用户并发送新增用户数据同步消息
     **/
    @Override
    public Map<String, Object> mailLogin(MailLoginRequest mailLoginRequest) {
        String code = (String) redisTemplate.opsForValue().get(mailLoginRequest.getMailNumber());
        log.info("邮箱验证码"+code);
        LambdaQueryWrapper<User> wrapper=new LambdaQueryWrapper<>();
        wrapper.eq(User::getMailNumber, mailLoginRequest.getMailNumber());
        User user = userMapper.selectOne(wrapper);
        Integer id=null;
        Map<String, Object> map = new HashMap<>(2);
        log.info(mailLoginRequest.getMailNumber());
        log.info(code);
        log.info(mailLoginRequest.getCaptcha());
        //同手机号
        if (user != null && passwordEncoder.matches(mailLoginRequest.getMailNumber(),user.getMailNumber()) &&code.equals(mailLoginRequest.getCaptcha()) ) {
            id=user.getId();
            map.put(USERIDENTITY, id);
            log.info(id.toString());

        }
        if(user ==null){
            User newUser =mailLoginRequest.toEntity().setCover("https://labilibili.com/user-cover/default.png");
            passwordEncoder.encode(newUser.getMailNumber());
            userMapper.insert(newUser);
            privilegeMapper.insert(new Privilege().setUserId(newUser.getId()));
            id= newUser.getId();
                ObjectMapper objectMapper = new ObjectMapper();
                Map<String, Object> userMap = objectMapper.convertValue(newUser, Map.class);
                userMap.put(TABLE_NAME, USER_TABLE_NAME);
                userMap.put(OPERATION_TYPE, OPERATION_TYPE_ADD);
                userMap.remove(TABLE_IGNORE_USERNAME);
                userMap.remove(TABLE_IGNORE_PASSWORD);
                userMap.remove(TABLE_IGNORE_PHONE_NUMBER);
                client.sendDBChangeNotice(userMap);
            map.put(USERIDENTITY, newUser.getId());
        }
        map.put(SHORT_TOKEN, JwtUtil.generateShortToken(id));
        map.put(LONG_TOKEN, JwtUtil.generateLongToken(id));
        return map;
    }

    //获取图像验证码
    /**
     * 生成图形验证码并关联会话存储
     * 生成包含随机数字和干扰线的图形验证码，将验证码图片转换为Base64编码字符串，
     * 并根据客户端是否携带SESSIONID Cookie决定生成新会话ID或复用现有会话ID，
     * 最终将验证码值与会话ID关联存储到Redis中，供后续登录验证使用
     * @throws IOException 当验证码图片生成或Base64编码过程中发生IO异常时抛出
     */
    @Override
    public Map<String, String> getCaptcha(HttpCookie cookie) throws IOException {
        Map<String, String> captchaMap = new HashMap<>(2); // 存储验证码结果数据
        StringBuffer code = new StringBuffer(); // 构建验证码文本值

        // 创建验证码图片缓冲区（宽度WIDTH/高度HEIGHT，RGB色彩模式）
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics(); // 获取绘图上下文
        Random random = new Random(); // 随机数生成器

        // 1. 绘制背景
        g.setColor(Color.WHITE); // 设置背景色为白色
        g.fillRect(0, 0, WIDTH, HEIGHT); // 填充整个图片区域

        // 2. 设置字体与绘制边框
        g.setFont(new Font("Times New Roman", Font.BOLD, 30)); // 设置验证码字体（加粗，30号）
        g.setColor(Color.BLACK); // 设置边框颜色为黑色
        g.drawRect(0, 0, WIDTH - 1, HEIGHT - 1); // 绘制边框（留出1px边距避免超出图片范围）

        // 3. 绘制干扰线（LINE_COUNT条随机颜色/位置的线段，增强安全性）
        for (int i = 0; i < LINE_COUNT; i++) {
            int xs = random.nextInt(WIDTH); // 起点X坐标
            int ys = random.nextInt(HEIGHT); // 起点Y坐标
            int xe = random.nextInt(WIDTH); // 终点X坐标
            int ye = random.nextInt(HEIGHT); // 终点Y坐标
            g.setColor(CaptchaUtil.getRandColor(1, 255)); // 随机生成线段颜色
            g.drawLine(xs, ys, xe, ye); // 绘制线段
        }

        // 4. 绘制验证码字符（CODE_COUNT个随机数字，取自CODE_SEQUENCE数组）
        for (int i = 0; i < CODE_COUNT; i++) {
            // 从数字序列中随机选取字符
            String strRand = String.valueOf(CODE_SEQUENCE[random.nextInt(CODE_SEQUENCE.length)]);
            g.setColor(CaptchaUtil.getRandColor(1, 255)); // 随机生成字符颜色
            g.drawString(strRand, (i + 1) * 30, 30); // 绘制字符（水平间距30px，垂直居中）
            code.append(strRand); // 拼接验证码文本
        }
        g.dispose(); // 释放绘图资源

        // 5. 处理验证码图片与存储
        String captcha = CaptchaUtil.imageToBase64(image); // 将图片转换为Base64编码字符串
        captchaMap.put(CAPTCHA_IMAGE, captcha); // 存储Base64图片
        captchaMap.put(CAPTCHA_CODE, code.toString()); // 存储验证码文本值

        // 6. 会话ID与验证码关联存储（Redis）
        if (cookie == null) {
            // 客户端无SESSIONID：生成新会话ID（UUID前15位），存入结果映射并关联验证码
            String sessionId = UUID.randomUUID().toString().substring(0, 15);
            captchaMap.put(SESSIONID, sessionId);
            redisTemplate.opsForValue().set(sessionId, code.toString()); // KEY: sessionId, VALUE: 验证码
        } else {
            // 客户端有SESSIONID：复用现有会话ID关联验证码
            redisTemplate.opsForValue().set(cookie.getValue(), code.toString()); // KEY: cookie值(sessionId), VALUE: 验证码
        }
        return captchaMap; // 返回验证码数据（图片、文本、新会话ID）
    }

    /**
     * 发送手机验证码
     * 生成6位随机数字验证码，通过短信服务发送至目标手机号，并将验证码存储到Redis中供后续登录验证使用
     * @param phoneNumber 目标手机号，用于接收验证码短信
     * @return Result<Boolean> - 操作结果封装：当前固定返回成功状态(true)
     * @throws ClientException 当短信发送服务调用过程中发生异常时抛出
     */
    @Override
    public Result<Boolean> sendPhoneNumberCaptcha( String phoneNumber) throws ClientException{
        // 生成6位随机数字验证码
        Random random=new Random();
        String number=""+random.nextInt(10)+random.nextInt(10)+random.nextInt(10)+random.nextInt(10)+random.nextInt(10)+random.nextInt(10);
        // 调用短信发送工具类发送验证码
        SendMessage.sendSms(phoneNumber,number);
        // 将验证码存储到Redis，以手机号为key，供后续登录验证
        redisTemplate.opsForValue().set(phoneNumber,number);
        // 返回操作成功结果
        return Result.data(true);
    }

    /**
     *发送邮箱验证码
     **/
    @Override
    public Result<Boolean> sendMailNumberCaptcha(String mailNumber){
        SimpleMailMessage message = new SimpleMailMessage();
        Random random=new Random();
        message.setTo(mailNumber);
        message.setSubject("验证码");
        String captcha=""+random.nextInt(10)+random.nextInt(10)+random.nextInt(10)+random.nextInt(10)+random.nextInt(10)+random.nextInt(10);
        redisTemplate.opsForValue().set(mailNumber,captcha);
        message.setText("aigcbilibilibili验证码:"+captcha);
        message.setFrom(from);
        log.info("send");
        log.info(from);
        mailSender.send(message);
        return Result.data(true);
    }

}
