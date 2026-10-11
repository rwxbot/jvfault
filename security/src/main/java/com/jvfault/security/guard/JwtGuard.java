package com.jvfault.security.guard;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvfault.security.jwt.JwtService;
import com.jvfault.web.http.HttpContext;
import com.jvfault.web.pipeline.Guard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JWT 鉴权守卫 - 校验 Authorization: Bearer 头，claims 以 JSON 存入
 * 路径参数表（供 claimsOf 读取）。
 *
 * @since v1.0.0 (2026)
 * @author jvfault team
 */
public class JwtGuard implements Guard {

    public static final String CLAIMS_ATTRIBUTE = "jvfault.auth.claims";

    private static final Logger log = LoggerFactory.getLogger(JwtGuard.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JwtService jwtService;
    private final String requiredRole;

    public JwtGuard(JwtService jwtService) {
        this(jwtService, null);
    }

    public JwtGuard(JwtService jwtService, String requiredRole) {
        this.jwtService = jwtService;
        this.requiredRole = requiredRole;
    }

    @Override
    public boolean canActivate(HttpContext context) {
        String authorization = context.getRequest().getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return false;
        }
        Map<String, Object> claims = jwtService.verify(authorization.substring(7).trim());
        if (claims == null) {
            return false;
        }
        if (requiredRole != null && !requiredRole.equals(claims.get("role"))) {
            return false;
        }
        try {
            context.getRequest().getPathParams().put(CLAIMS_ATTRIBUTE, MAPPER.writeValueAsString(claims));
            return true;
        } catch (Exception e) {
            // 失败仍返回 false（fail-closed，鉴权语义不变），但原来这里完全静默：
            // 「用户没带 token」「token 过期」和「服务端 claims 序列化炸了」在调用方
            // 看来是同一个 false，线上根本没法区分配置错误和正常拒绝。补一条 warn。
            log.warn("claims 写入请求属性失败，本次请求按未鉴权处理", e);
            return false;
        }
    }

    /**
     * 读取当前请求的 claims（由 JwtGuard 注入；未鉴权返回空 Map）。
     */
    public static Map<String, Object> claimsOf(HttpContext context) {
        String raw = context.getRequest().getPathParams().get(CLAIMS_ATTRIBUTE);
        if (raw == null) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(raw, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
