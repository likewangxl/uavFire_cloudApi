package com.dji.sample.map.controller;

import com.dji.sdk.common.HttpResultResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 为 Pilot 2 提供地图元素图标查询接口。
 *
 * <p>当前测试环境没有自定义元素图标资源，返回空数组即可避免前端持续 404。</p>
 *
 * @author likewangx
 */
@RestController
public class ElementIconController {

    /**
     * 查询工作空间下的元素图标列表。
     *
     * @param workspaceId 工作空间标识
     * @return 空图标列表
     */
    @GetMapping("${url.map.prefix}${url.map.version}/workspaces/{workspace_id}/element-icons")
    public HttpResultResponse<List<Object>> getElementIcons(@PathVariable("workspace_id") String workspaceId) {
        return HttpResultResponse.success(List.of());
    }
}
