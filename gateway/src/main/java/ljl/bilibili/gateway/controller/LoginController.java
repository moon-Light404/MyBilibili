package ljl.bilibili.gateway.controller;
import com.aliyuncs.exceptions.ClientException;
import ljl.bilibili.gateway.service.LoginService;
import ljl.bilibili.gateway.util.JwtUtil;
import ljl.bilibili.gateway.util.SendMessage;
import ljl.bilibili.gateway.vo.request.MailLoginRequest;
import ljl.bilibili.gateway.vo.request.PasswordLoginRequest;
import ljl.bilibili.gateway.vo.response.LoginResponse;
import ljl.bilibili.gateway.service.impl.LoginServiceImpl;
import ljl.bilibili.gateway.vo.request.PhoneNumberLoginRequest;
import ljl.bilibili.util.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import javax.annotation.Resource;
import java.io.IOException;
import java.util.Map;
import static ljl.bilibili.gateway.constant.Constant.*;

@RestController
@RequestMapping("/login")
@CrossOrigin(value = "*")
@Slf4j
public class LoginController {
    @Resource
    LoginService loginService;

    /**
     *登录成功返回放在缓存中的短token和放在http-only-cookie中的长token，失败返回失败响应
     */
    /**
     * 密码登录接口：验证用户密码并生成认证令牌
     * 从请求中获取会话ID（用于验证码校验），调用服务层验证用户凭证，
     * 若验证成功，生成短期令牌（响应头）和长期令牌（HTTP-only Cookie）并返回用户ID；
     * 验证失败则返回登录失败状态
     * @param passwordLoginRequest 登录请求参数（包含用户名、密码、验证码等）
     * @param exchange 服务交换对象，用于获取请求Cookie（会话ID）和设置响应令牌（头/Cookie）
     * @return Result<LoginResponse> - 登录结果封装，包含登录状态和用户ID（成功时）
     */
    @PostMapping("/passwordLogin")
    public Result<LoginResponse> passwordLogin(@RequestBody PasswordLoginRequest passwordLoginRequest, ServerWebExchange exchange) {
        // 从请求Cookie中获取会话ID（用于关联Redis中的验证码，确保验证码与当前会话匹配）
        String sessionId = exchange.getRequest().getCookies().getFirst(SESSIONID).getValue();

        // 调用服务层执行密码登录逻辑（验证用户名密码、验证码有效性等）
        Map<String, Object> map = loginService.passwordLogin(passwordLoginRequest, sessionId);

        // 登录成功（服务层返回非空map，包含用户ID和令牌）
        if (map.size() > 0) {
            // 从返回结果中提取用户ID、短期令牌、长期令牌
            Integer userId = (Integer) map.get(USERIDENTITY);
            String shortJwt = (String) map.get(SHORT_TOKEN);
            String longJwt = (String) map.get(LONG_TOKEN);

            // 将短期令牌放入响应头（供前端存储，用于后续API请求的身份验证）
            exchange.getResponse().getHeaders().add(SHORT_TOKEN, shortJwt);

            // 构建长期令牌的HTTP-only Cookie（增强安全性，防止客户端脚本访问，降低XSS风险）
            ResponseCookie cookie = ResponseCookie.from(LONG_TOKEN, longJwt)
                    .httpOnly(true)  // 设置HTTP-only，禁止JavaScript读取
                    .path("/")       // Cookie生效路径为全站
                    .maxAge(60 * 30) // 有效期30分钟（与短期令牌过期时间一致）
                    .build();
            exchange.getResponse().addCookie(cookie);

            // 返回登录成功结果（包含用户ID和成功状态）
            return Result.data(new LoginResponse().setStatus(true).setUserId(userId));
        }

        // 登录失败（服务层返回空map），返回失败状态
        return Result.data(new LoginResponse().setStatus(false));
    }

    @PostMapping("/phoneNumberLogin")
    public Result<LoginResponse> phoneNumberLogin(@RequestBody PhoneNumberLoginRequest phoneNumberLoginRequest, ServerWebExchange exchange) {
        Map<String, Object> map = loginService.phoneNumberLogin(phoneNumberLoginRequest);
        if (map != null) {
            Integer userId=(Integer) map.get(USERIDENTITY);
            String shortJwt = (String) map.get(SHORT_TOKEN);
            String longJwt=(String)map.get(LONG_TOKEN);
            exchange.getResponse().getHeaders().add(SHORT_TOKEN, shortJwt);
            ResponseCookie cookie = ResponseCookie.from(LONG_TOKEN, longJwt)
                    .httpOnly(true)
                    .path("/")
                    .maxAge(60 * 30)
                    .build();
            exchange.getResponse().addCookie(cookie);
            log.info("手机号登录的id"+userId);
            return Result.data(new LoginResponse().setStatus(true).setUserId(userId));
        }
        return Result.data(new LoginResponse().setStatus(false));
    }
    @PostMapping("/mailLogin")
    public Result<LoginResponse> mailLogin(@RequestBody MailLoginRequest mailLoginRequest, ServerWebExchange exchange) {
        Map<String, Object> map = loginService.mailLogin(mailLoginRequest);
        if (map != null) {
            Integer userId=(Integer) map.get(USERIDENTITY);
            String shortJwt = (String) map.get(SHORT_TOKEN);
            String longJwt=(String)map.get(LONG_TOKEN);
            exchange.getResponse().getHeaders().add(SHORT_TOKEN, shortJwt);
            ResponseCookie cookie = ResponseCookie.from(LONG_TOKEN, longJwt)
                    .httpOnly(true)
                    .path("/")
                    .maxAge(60 * 30)
                    .build();
            exchange.getResponse().addCookie(cookie);
            log.info("邮箱登录的id"+userId);
            return Result.data(new LoginResponse().setStatus(true).setUserId(userId));
        }
        return Result.data(new LoginResponse().setStatus(false));
    }
    /**
     *刷新token
     */
    /**
     * 令牌刷新接口
     * 用于刷新用户的短期访问令牌和长期刷新令牌。从请求头获取短期令牌，从Cookie获取长期令牌，
     * 调用JwtUtil刷新令牌后，将新的短期令牌放入响应头，新的长期令牌以HTTP-only Cookie形式返回，
     * 确保令牌安全性并延长用户会话有效期
     * @param exchange ServerWebExchange对象，用于获取请求信息（令牌）和设置响应信息（新令牌/Cookie）
     * @return Result<Boolean> - 操作结果封装，固定返回成功状态(true)
     */
    @PostMapping("/refreshToken")
    public Result<Boolean> refreshToken(ServerWebExchange exchange){
        log.info("刷新token");
        // 从请求头获取短期令牌
        String shortToken= exchange.getRequest().getHeaders().getFirst(SHORT_TOKEN);
        log.info(shortToken);
        // 从Cookie获取长期令牌
        String longToken=exchange.getRequest().getCookies().getFirst(LONG_TOKEN).getValue();

        // 刷新短期令牌并设置到响应头
        exchange.getResponse().getHeaders().set(SHORT_TOKEN, JwtUtil.refreshToken(shortToken,0));

        // 刷新长期令牌并构建HTTP-only Cookie（增强安全性，防止JS访问）
        ResponseCookie cookie = ResponseCookie.from(LONG_TOKEN, JwtUtil.refreshToken(longToken,1))
                .httpOnly(true)  // 设置HTTP-only，避免客户端脚本访问
                .path("/")       // Cookie生效路径
                .maxAge(LONG_TOKEN_EXPIRATION )  // Cookie有效期（与长期令牌过期时间一致）
                .build();
        exchange.getResponse().addCookie(cookie);

        // 返回刷新成功结果
        return Result.success(true);
    }
    /**
     *获取验证码，并将sessionId返回给客户端，后续刷新验证码时根据sessionId更换redis中验证码值
     * 生成图形验证码并返回其Base64编码字符串，同时管理会话ID（SESSIONID）的Cookie存储。
     * 若客户端无SESSIONID Cookie，则生成新会话ID并通过HTTP-only Cookie返回；若已有，则复用现有会话ID。
     * 验证码值会与会话ID关联存储在Redis中，用于后续登录验证时的验证码比对
     * @param exchange ServerWebExchange对象，用于获取请求Cookie（SESSIONID）和设置响应Cookie
     * @return Result<String> - 操作结果封装，包含Base64编码的图形验证码图片字符串
     * @throws IOException 当验证码图片生成或Base64编码过程中发生IO异常时抛出
     */
    @GetMapping("/getCaptcha")
    public Result<String> getCaptcha(ServerWebExchange exchange) throws IOException {
        // 从请求中获取SESSIONID Cookie（若存在）
        HttpCookie cookie = exchange.getRequest().getCookies().getFirst(SESSIONID);

        // 调用服务层生成验证码图片及相关数据（包含Base64图片、验证码值、可能的新SESSIONID）
        Map<String, String> map = loginService.getCaptcha(cookie);

        // 若服务层返回了新的SESSIONID（说明客户端无有效会话），则创建HTTP-only Cookie存储该SESSIONID
        if (map.get(SESSIONID) != null) {
            ResponseCookie sessionCookie = ResponseCookie.from(SESSIONID, map.get(SESSIONID))
                    .httpOnly(true)  // 设置HTTP-only，防止客户端脚本访问，提升安全性
                    .path("/")       // Cookie生效路径为全站
                    .maxAge(60 * 30) // Cookie有效期30分钟（与验证码有效期一致）
                    .build();
            exchange.getResponse().addCookie(sessionCookie);
        }
        // 返回Base64编码的验证码图片字符串给前端
        return Result.data(map.get(CAPTCHA_IMAGE));
    }
    /**
     *发送手机验证码
     */
    @GetMapping("/phoneNumberCaptcha/{phoneNumber}")
    public Result<Boolean> sendPhoneNumberCaptcha(@PathVariable String phoneNumber) throws ClientException {
        return loginService.sendPhoneNumberCaptcha(phoneNumber);
    }

    @GetMapping("/mailNumberCaptcha/{mailNumber}")
    public Result<Boolean> sendMailNumberCaptcha(@PathVariable String mailNumber){

        return loginService.sendMailNumberCaptcha(mailNumber);
    }
}
