package com.yx.uavfire.component;

import org.springframework.stereotype.Component;

import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

import static com.yx.uavfire.component.AuthInterceptor.PARAM_TOKEN;

/**
 * @author sean.zhou
 * @version 0.1
 * @date 2021/11/22
 */
@Component
public class CorsFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;
        res.addHeader("Access-Control-Allow-Credentials", "true");
        res.addHeader("Access-Control-Allow-Origin", "*");
        res.addHeader("Access-Control-Allow-Methods", "GET, POST, DELETE, PUT");
        res.addHeader("Access-Control-Allow-Headers", "Access-Control-Allow-Headers," +
                "Authorization, Content-Length, X-CSRF-Token, Token,session,X_Requested_With,Accept, "+
                        "Origin, Host, Connection, Accept-Encoding, Accept-Language,DNT, X-CustomHeader, Keep-Alive," +
                        " User-Agent, X-Requested-With, If-Modified-Since, Cache-Control, Content-Type, Pragma," +
                        "X-Request-Id, X-Idempotency-Key," + PARAM_TOKEN);
        if (isApiRequest(req.getRequestURI())) {
            res.setHeader("Cache-Control", "no-store, no-cache, max-age=0, must-revalidate");
            res.setHeader("Pragma", "no-cache");
            res.setHeader("Expires", "0");
        }
        if (req.getMethod().equals("OPTIONS")) {
            return;
        }
        filterChain.doFilter(request, response);
    }

    private boolean isApiRequest(String requestUri) {
        return requestUri.startsWith("/manage/api/")
                || requestUri.startsWith("/media/api/")
                || requestUri.startsWith("/api/fire/");
    }
}
