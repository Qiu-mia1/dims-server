package com.isim.dims.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.isim.dims.dto.UserDashboardConfigDetailResponse;
import com.isim.dims.dto.UserDashboardConfigExportFile;
import com.isim.dims.dto.UserDashboardConfigListResponse;

/**
 * 用户个性化配置方案 Service（数据回放看板）
 *
 * <p>存储模型：<b>一个用户可有多套方案</b>，方案名在单用户内唯一
 * （{@code uk_user_dashboard_config_user_name}）。配置 JSON 由前端组装，
 * 后端只负责原样落库与读回，不解析内容语义。</p>
 *
 * <p>全部方法按 {@code userId} 做归属隔离：定位单个方案一律用
 * {@code (id, user_id)} 双条件，他人名下的 configId 表现为「不存在」，不泄露其存在性。</p>
 *
 * <p><b>签名刻意使用散参数而非请求体 DTO</b>：Service 只表达领域参数
 * （谁、哪个方案、什么名字、什么配置），传输层结构变化（如把 userId 从路径挪到请求体）
 * 不需要改动 Service 与其测试。</p>
 *
 * @feature: replay
 */
public interface UserDashboardConfigService {

    /**
     * 分页查询某用户的方案列表（摘要，不含 config_json），按 created_at DESC 排序。
     *
     * @param userId   IAMS 用户 UUID
     * @param page     页码，从 1 开始（由 Controller 钳制）
     * @param pageSize 每页条数（由 Controller 钳制在 [1,100]）
     * @return 分页摘要列表；该用户无方案时 items 为空数组、total 为 0（<b>正常状态，非错误</b>）
     */
    UserDashboardConfigListResponse list(String userId, int page, int pageSize);

    /**
     * 新建方案。
     *
     * <p>方案名在同一用户内重复时抛 {@code USER_CONFIG_NAME_CONFLICT}（→409）。</p>
     *
     * @param userId      IAMS 用户 UUID
     * @param name        方案名（必填、非空白、≤120，内部 trim）
     * @param description 方案描述（可空；null / 空白一律写 NULL）
     * @param configJson  配置 JSON（必填）；仅原样序列化落库
     * @return 新建后的方案详情
     */
    UserDashboardConfigDetailResponse create(String userId, String name, String description, JsonNode configJson);

    /**
     * 查询某用户下指定方案的详情（含 config_json）。
     *
     * @param userId   IAMS 用户 UUID
     * @param configId 方案 ID
     * @return 方案详情
     * @throws RuntimeException {@code USER_CONFIG_NOT_FOUND} —— 该 userId 下不存在此 configId
     */
    UserDashboardConfigDetailResponse getDetail(String userId, String configId);

    /**
     * 编辑方案（<b>全量替换语义</b>）：name / configJson 必填，description 传 null 即清空。
     *
     * <p>改名撞重名时抛 {@code USER_CONFIG_NAME_CONFLICT}（→409）。</p>
     *
     * @param userId      IAMS 用户 UUID
     * @param configId    方案 ID
     * @param name        新方案名
     * @param description 新描述（null 表示清空）
     * @param configJson  新配置 JSON
     * @return 更新后的方案详情
     * @throws RuntimeException {@code USER_CONFIG_NOT_FOUND} —— 该 userId 下不存在此 configId
     */
    UserDashboardConfigDetailResponse update(String userId, String configId,
                                             String name, String description, JsonNode configJson);

    /**
     * 删除方案（物理删除——全库无逻辑删除机制）。
     *
     * @param userId   IAMS 用户 UUID
     * @param configId 方案 ID
     * @throws RuntimeException {@code USER_CONFIG_NOT_FOUND} —— 该 userId 下不存在此 configId
     */
    void delete(String userId, String configId);

    /**
     * 导出方案为可分享的 JSON 文件结构（<b>自描述包装</b>）。
     *
     * <p>调用方负责把返回值序列化并按 {@code <name>.json} 命名下载，
     * 以及清理文件名中的非法字符（Web 层关注点，不在本层处理）。</p>
     *
     * @param userId   IAMS 用户 UUID
     * @param configId 方案 ID
     * @return 导出文件结构（version / exported_at / name / description / config_json）
     * @throws RuntimeException {@code USER_CONFIG_NOT_FOUND} —— 该 userId 下不存在此 configId
     */
    UserDashboardConfigExportFile export(String userId, String configId);

    /**
     * 导入方案文件，达成「同事间分享配置」。
     *
     * <p><b>方案名以文件名为准</b>（去掉 {@code .json} 扩展名、trim、截断到上限）；
     * 文件名不可用时回落到文件内 {@code name} 字段。</p>
     *
     * <p>文件必须是 {@link UserDashboardConfigExportFile} 结构，其中的 {@code config_json}
     * 为必填——刻意<b>不</b>把「裸配置 JSON」当合法输入，避免格式语义含糊：
     * 宁可明确报 400，也不静默猜测用户意图。</p>
     *
     * <p>名称与既有方案重复时抛 {@code USER_CONFIG_NAME_CONFLICT}（→409），
     * <b>不做自动改名</b>（如「方案A(1)」），由用户显式改文件名后重试。</p>
     *
     * @param userId   IAMS 用户 UUID（导入到谁名下）
     * @param fileName 上传的原始文件名（含扩展名，可能带路径前缀）
     * @param content  文件字节内容
     * @return 导入后的方案详情
     * @throws RuntimeException {@code USER_CONFIG_IMPORT_INVALID} —— 文件为空 / 非法 JSON / 缺 config_json / 文件名非法
     * @throws RuntimeException {@code USER_CONFIG_NAME_CONFLICT} —— 派生出的方案名已存在
     */
    UserDashboardConfigDetailResponse importConfig(String userId, String fileName, byte[] content);
}
