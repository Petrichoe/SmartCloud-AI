package com.tianji.gateway.filter;

import com.tianji.authsdk.gateway.util.AuthUtil;
import com.tianji.common.domain.R;
import com.tianji.common.domain.dto.LoginUserDTO;
import com.tianji.gateway.config.AuthProperties;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

import static com.tianji.auth.common.constants.JwtConstants.*;

@Component
public class AccountAuthFilter implements GlobalFilter, Ordered {

    private final AuthUtil authUtil;
    private final AuthProperties authProperties;
    private final AntPathMatcher antPathMatcher = new AntPathMatcher();

    public AccountAuthFilter(AuthUtil authUtil, AuthProperties authProperties) {
        this.authUtil = authUtil;
        this.authProperties = authProperties;
    }


    @Override//因为你的类实现了 GlobalFilter（全局过滤器），网关在处理每一个进入系统的 HTTP 请求时，都会把这两个对象传给 filter 方法。
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 1.获取请求request信息
        ServerHttpRequest request = exchange.getRequest();
        // String method = request.getMethodValue();

        //构建 antPath，格式如 "POST:/accounts/login"
        String method = request.getMethod().name();
        String path = request.getPath().toString();
        String antPath = method + ":" + path;

        // 2.判断是否是无需登录的路径
        if(isExcludePath(antPath)){
            // 直接放行:不需要token
            return chain.filter(exchange);
        }

        // 3.尝试获取用户信息
        List<String> authHeaders = exchange.getRequest().getHeaders().get(AUTHORIZATION_HEADER);
        String token = authHeaders == null ? "" : authHeaders.get(0);
        R<LoginUserDTO> r = authUtil.parseToken(token);

        // 4.如果用户是登录状态，尝试更新请求头，传递用户信息
        if (r.success()) {
            exchange.mutate()
                    .request(builder -> builder
                            .header(USER_HEADER, r.getData().getUserId().toString())
                            .header(TOKEN_HEADER, token)
                    )
                    .build();
        }

        // 5.校验权限
        authUtil.checkAuth(antPath, r);

        // 6.放行
        return chain.filter(exchange);
    }

    private boolean isExcludePath(String antPath) {
        for (String pathPattern : authProperties.getExcludePath()) {
            if(antPathMatcher.match(pathPattern, antPath)){
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return 1000;
    }
}
