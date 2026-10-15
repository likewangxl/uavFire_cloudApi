package com.yx.uavfire.fc100.mission.service;

import com.yx.uavfire.common.model.CustomClaim;
import com.yx.uavfire.component.AuthInterceptor;
import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.manage.config.AuthSecurityProperties;
import com.yx.uavfire.manage.model.enums.UserTypeEnum;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Fc100OperatorResolverTest {

    private final Fc100OperatorResolver resolver = resolver();

    @Test
    void tokenUsernameOverridesBodyAndMapsWebToCommander() {
        MockHttpServletRequest request = request("commander-1", UserTypeEnum.WEB.getVal());

        Fc100OperatorResolver.ResolvedOperator operator = resolver.resolve(request, "body-operator");

        assertEquals("commander-1", operator.operatorId);
        assertEquals("COMMANDER", operator.role);
        assertTrue(operator.authenticated);
        resolver.requireForceFail(request);
    }

    @Test
    void configuredAdminUsernameIsAdmin() {
        MockHttpServletRequest request = request("root", UserTypeEnum.WEB.getVal());

        assertEquals("ADMIN", resolver.resolve(request, "body-operator").role);
        resolver.requireForceFail(request);
    }

    @Test
    void pilotAndAnonymousCannotForceFail() {
        MockHttpServletRequest pilot = request("pilot-1", UserTypeEnum.PILOT.getVal());
        assertEquals("PILOT", resolver.resolve(pilot, "body-operator").role);
        assertThrows(Fc100BusinessException.class, () -> resolver.requireForceFail(pilot));

        MockHttpServletRequest anonymous = new MockHttpServletRequest();
        Fc100OperatorResolver.ResolvedOperator fallback = resolver.resolve(anonymous, "body-operator");
        assertEquals("body-operator", fallback.operatorId);
        assertFalse(fallback.authenticated);
        assertThrows(Fc100BusinessException.class, () -> resolver.requireForceFail(anonymous));
    }

    private static Fc100OperatorResolver resolver() {
        AuthSecurityProperties properties = new AuthSecurityProperties();
        properties.setAdminUsernames("root, ops-admin");
        return new Fc100OperatorResolver(properties);
    }

    private static MockHttpServletRequest request(String username, int userType) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        CustomClaim claim = new CustomClaim();
        claim.setId("uid");
        claim.setUsername(username);
        claim.setUserType(userType);
        claim.setWorkspaceId("ws");
        request.setAttribute(AuthInterceptor.TOKEN_CLAIM, claim);
        return request;
    }
}
