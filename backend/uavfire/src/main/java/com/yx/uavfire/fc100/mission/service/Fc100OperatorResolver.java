package com.yx.uavfire.fc100.mission.service;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.component.AuthInterceptor;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;
import com.yx.uavfire.manage.config.AuthSecurityProperties;
import com.yx.uavfire.manage.model.enums.UserTypeEnum;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.Set;

@Component
public class Fc100OperatorResolver {

    private final Set<String> adminUsernames;

    public Fc100OperatorResolver(AuthSecurityProperties properties) {
        this.adminUsernames = properties == null ? Set.of() : properties.adminUsernameSet();
    }

    public ResolvedOperator resolve(HttpServletRequest request, String bodyOperatorId) {
        CustomClaim claim = claim(request);
        if (claim == null || !StringUtils.hasText(claim.getUsername())) {
            return new ResolvedOperator(bodyOperatorId, null, false);
        }
        return new ResolvedOperator(claim.getUsername(), roleOf(claim), true);
    }

    public String requireOperatorId(HttpServletRequest request, String bodyOperatorId) {
        ResolvedOperator operator = resolve(request, bodyOperatorId);
        if (StringUtils.hasText(operator.operatorId)) {
            return operator.operatorId;
        }
        throw new Fc100BusinessException(Fc100ErrorCode.INVALID_PARAM, "operator is required");
    }

    public void requireForceFail(HttpServletRequest request) {
        ResolvedOperator operator = resolve(request, null);
        if (!operator.authenticated) {
            throw new Fc100BusinessException(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN,
                    "force-fail requires an authenticated commander or admin");
        }
        if (!"ADMIN".equals(operator.role) && !"COMMANDER".equals(operator.role)) {
            throw new Fc100BusinessException(Fc100ErrorCode.STATUS_TRANSITION_FORBIDDEN,
                    "force-fail is not allowed for role " + operator.role);
        }
    }

    private String roleOf(CustomClaim claim) {
        if (adminUsernames.contains(claim.getUsername())) {
            return "ADMIN";
        }
        if (claim.getUserType() != null && claim.getUserType() == UserTypeEnum.WEB.getVal()) {
            return "COMMANDER";
        }
        if (claim.getUserType() != null && claim.getUserType() == UserTypeEnum.PILOT.getVal()) {
            return "PILOT";
        }
        return "OPERATOR";
    }

    private static CustomClaim claim(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object value = request.getAttribute(AuthInterceptor.TOKEN_CLAIM);
        return value instanceof CustomClaim ? (CustomClaim) value : null;
    }

    public static final class ResolvedOperator {
        public final String operatorId;
        public final String role;
        public final boolean authenticated;

        public ResolvedOperator(String operatorId, String role, boolean authenticated) {
            this.operatorId = operatorId;
            this.role = role;
            this.authenticated = authenticated;
        }
    }
}
