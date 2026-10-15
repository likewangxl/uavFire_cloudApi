package com.yx.uavfire.fc100.deliverysync;

import com.yx.uavfire.fc100.common.Fc100BusinessException;
import com.yx.uavfire.fc100.common.Fc100ErrorCode;

/** Hook release is only legal on the release-hook path after the release policy passes. */
public final class HookCommandGuard {

    public static final String HOIST_HOOK = "hoist_hook_control";
    public static final String RELEASE_AUTHORIZED = "releaseAuthorized";

    private HookCommandGuard() {
    }

    public static boolean isHook(String method) {
        return HOIST_HOOK.equals(method);
    }

    public static void rejectUnlessAuthorized(String method, boolean releaseAuthorized) {
        if (isHook(method) && !releaseAuthorized) {
            throw new Fc100BusinessException(Fc100ErrorCode.RELEASE_CAPABILITY_UNCONFIRMED,
                    "hoist_hook_control must use release-hook after the release policy passes");
        }
    }
}
