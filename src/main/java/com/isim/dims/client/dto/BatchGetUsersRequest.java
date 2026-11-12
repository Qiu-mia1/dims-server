package com.isim.dims.client.dto;

import lombok.Data;

import java.util.List;

/**
 * 批量用户信息查询请求
 *
 * <p>契约来源：platform-contracts/apis/iams.openapi.yaml → POST /users/batch-get。
 * {@code user_ids} 与 {@code usernames} 二选一，单次最多 100 个。</p>
 *
 * @feature: infra
 */
@Data
public class BatchGetUsersRequest {

    /** 用户 ID 列表（UUID），与 usernames 二选一 */
    private List<String> userIds;

    /** 用户名列表，与 user_ids 二选一 */
    private List<String> usernames;
}
