package com.isim.dims.client.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量用户信息查询结果
 *
 * <p>契约来源：platform-contracts/apis/iams.openapi.yaml → POST /users/batch-get 响应。</p>
 *
 * @feature: infra
 */
@Data
public class BatchGetUsersResult {

    /** 用户公开信息列表 */
    private List<UserInfo> items;
}
