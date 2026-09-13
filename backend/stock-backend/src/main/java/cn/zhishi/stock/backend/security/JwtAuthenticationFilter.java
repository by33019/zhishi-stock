package cn.zhishi.stock.backend.security;

import cn.zhishi.stock.system.auth.AccessTokenBlacklist;
import cn.zhishi.stock.system.auth.AccessTokenPrincipal;
import cn.zhishi.stock.system.auth.InvalidAccessTokenException;
import cn.zhishi.stock.system.auth.JwtAccessTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtAccessTokenService tokens;
    private final AccessTokenBlacklist blacklist;

    public JwtAuthenticationFilter(
            JwtAccessTokenService tokens,
            AccessTokenBlacklist blacklist) {
        this.tokens = tokens;
        this.blacklist = blacklist;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            authenticate(authorization.substring(BEARER_PREFIX.length()));
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            AccessTokenPrincipal principal = tokens.verify(token);
            if (blacklist.contains(principal.jti())) {
                return;
            }
            var authorities = principal.permissions().stream()
                    .map(SimpleGrantedAuthority::new)
                    .toList();
            var authentication = new UsernamePasswordAuthenticationToken(
                    principal, token, authorities);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (InvalidAccessTokenException exception) {
            SecurityContextHolder.clearContext();
        }
    }
}
