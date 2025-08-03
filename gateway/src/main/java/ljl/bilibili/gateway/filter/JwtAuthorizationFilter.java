package ljl.bilibili.gateway.filter;

import io.jsonwebtoken.Claims;
import ljl.bilibili.gateway.constant.Constant;
import ljl.bilibili.gateway.util.JwtUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Collections;

import static ljl.bilibili.gateway.constant.Constant.JWT_ROLE;
/**
 *自定义过滤器
 **/
//@Component
@Slf4j
public class JwtAuthorizationFilter implements WebFilter {

    /**
     *打印请求路径，用自定义请求头隔绝csrf攻击，取出token认证用户与验证权限
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();  // 获取当前请求的路径（如 /video/123）
        // 跟踪请求路径：排除静态资源(webjars)、文档(swagger)、API路径后打印日志
        if (!path.contains("webjars") && !path.contains("swagger") && !path.contains("api")) {
            log.info(exchange.getRequest().getURI().getPath());  // 打印非冗余路径的请求日志（如业务接口）
        }
        String jwt = exchange.getRequest().getHeaders().getFirst(Constant.SHORT_TOKEN);
        String aigcBiliBiliHeader = exchange.getRequest().getHeaders().getFirst(Constant.SAFE_REQUEST_HEADER);
        //如果没有该请求头则是非网站源发起的请求
        if (aigcBiliBiliHeader == null) {
            exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
            return exchange.getResponse().setComplete();
        } else {
            log.info("安全");
        }
//        如果token不为空则设置权限到security上下文中
        if (jwt != null) {
            Claims claims = JwtUtil.getClaimsFromToken(jwt); // 签名验证在此处自动触发
            String role = claims.get(JWT_ROLE, String.class);
        // 创建用户权限对象：固定赋予 "role:user" 权限（与SecurityConfig中权限要求对应）
        SimpleGrantedAuthority authority = new SimpleGrantedAuthority(JWT_ROLE + ":" + "user");

        // 创建认证对象：模拟用户认证信息（用户名、密码、权限列表）
        Authentication authentication = new UsernamePasswordAuthenticationToken(
            "username",  // 硬编码用户名（实际应从JWT令牌解析，当前为占位值）
            "password",  // 硬编码密码（无需实际校验，仅为构造函数必填参数）
            Collections.singletonList(authority)  // 用户权限列表（仅包含 "role:user"）
        );

        // 创建安全上下文并设置认证信息
        SecurityContext context = new SecurityContextImpl();
        context.setAuthentication(authentication);  // 将认证对象存入安全上下文

        // 将安全上下文写入请求链，使后续拦截器/控制器能获取用户认证信息
        return chain.filter(exchange)
            .contextWrite(ReactiveSecurityContextHolder.withSecurityContext(Mono.just(context)));
        }
//        为空则返回401，需要登录
        else {
            exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
            return chain.filter(exchange);
        }
    }
}


