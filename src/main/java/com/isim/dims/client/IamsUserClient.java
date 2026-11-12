package com.isim.dims.client;

import com.isim.dims.client.dto.BatchGetUsersRequest;
import com.isim.dims.client.dto.BatchGetUsersResult;
import com.isim.dims.client.dto.UserInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * IAMS 用户信息查询 Client（指南第 3 层：业务数据查询）
 *
 * <p>契约来源：platform-contracts/apis/iams.openapi.yaml
 * （GET /v1/users/{user_id}、POST /v1/users/batch-get）。</p>
 *
 * <p><b>启用条件</b>（基础设施缺口，见汇报）：</p>
 * <ol>
 *   <li>启动 Nacos 注册中心，并开启服务发现：
 *       {@code spring.cloud.nacos.discovery.enabled=true} +
 *       {@code spring.cloud.nacos.discovery.server-addr=<nacos地址>}（默认关闭）；</li>
 *   <li>在 IAMS 后台创建 OAuth2 客户端（client_id/client_secret，
 *       grant_type 含 client_credentials），并实现服务间调用 token 获取
 *       （指南 §4.4，IamsClientConfig 预留）；</li>
 *   <li>调用时经统一网关（{@code /iams/} 前缀）或直连 IAMS，按宪法 §7 透传
 *       X-Trace-Id（见 config/FeignConfig）。</li>
 * </ol>
 *
 * @feature: infra
 */
@FeignClient(name = "iams", contextId = "iamsUserClient")
public interface IamsUserClient {

    /**
     * 查询单个用户基本信息
     *
     * @param userId 用户 UUID
     * @return 用户公开信息；404 时抛 FeignException.NotFound
     */
    @GetMapping("/iams/v1/users/{userId}")
    UserInfo getUser(@PathVariable("userId") String userId);

    /**
     * 批量查询用户基本信息（单次最多 100 个 ID）
     *
     * @param request user_ids 与 usernames 二选一
     * @return 用户公开信息列表
     */
    @PostMapping("/iams/v1/users/batch-get")
    BatchGetUsersResult batchGetUsers(@RequestBody BatchGetUsersRequest request);
}
