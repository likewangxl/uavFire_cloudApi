package com.yx.uavfire.manage.service;

import com.yx.uavfire.manage.model.dto.UserDTO;
import com.yx.uavfire.manage.model.dto.UserListDTO;
import com.dji.sdk.common.HttpResultResponse;
import com.dji.sdk.common.PaginationData;

import java.util.Optional;

public interface IUserService {

    /**
     * Query user's details based on username.
     * @param username
     * @param workspaceId
     * @return
     */
    HttpResultResponse getUserByUsername(String username, String workspaceId);

    /**
     * Verify the username and password to log in.
     * @param username
     * @param password
     * @param flag
     * @param captcha
     * @param captchaToken
     * @return
     */
    HttpResultResponse userLogin(String username, String password, Integer flag,
                                 String captcha, String captchaToken);

    /**
     * Log in as the demo account (adminPC) without password or captcha.
     * @return
     */
    HttpResultResponse demoLogin();

    /**
     * Create a user object containing a new token.
     * @param token
     * @return
     */
    Optional<UserDTO> refreshToken(String token);

    /**
     * Query information about all users in a workspace.
     * @param workspaceId   uuid
     * @return
     */
    PaginationData<UserListDTO> getUsersByWorkspaceId(long page, long pageSize, String workspaceId);

    Boolean updateUser(String workspaceId, String userId, UserListDTO user);
}
