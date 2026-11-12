package com.isim.dims.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 用户个性化配置方案 —— <b>导出文件结构</b>（自描述包装）
 *
 * <p>导出为 {@code <方案名>.json}，文件内容是本对象的 JSON 序列化结果：</p>
 * <pre>
 * {
 *   "version": "1.0",
 *   "exported_at": "2026-09-17T02:00:00.000Z",
 *   "name": "泊车关注方案",
 *   "description": "关注障碍物与车道线",
 *   "config_json": { ... }
 * }
 * </pre>
 *
 * <p><b>为什么是自描述包装而不是裸 config_json</b>：{@code description} 属于方案元数据，
 * 若只导出裸配置则同事导入后描述恒为空——分享出去的是「半个方案」。
 * 包装格式同时给 {@code version} 留了扩展位，将来格式演进可做兼容分支。</p>
 *
 * <p><b>导入时的名称来源</b>：以<b>文件名</b>为准（用户要求「默认把文件名称当作配置名称」），
 * 本结构里的 {@code name} 仅作为文件名非法/缺失时的兜底。</p>
 *
 * <p>注意：全局 Jackson {@code property-naming-strategy: SNAKE_CASE}，
 * 故落盘字段为 {@code exported_at} / {@code config_json} 而非驼峰。</p>
 *
 * @feature: replay
 */
@Data
public class UserDashboardConfigExportFile {

    /** 导出文件格式版本，供将来做兼容解析；当前恒为 {@value #CURRENT_VERSION} */
    public static final String CURRENT_VERSION = "1.0";

    /** 格式版本 */
    private String version = CURRENT_VERSION;

    /** 导出时刻（RFC3339，UTC） */
    private String exportedAt;

    /** 方案名称（导入时若文件名不可用则回落到此值） */
    private String name;

    /** 方案描述；{@code null} 表示无描述 */
    private String description;

    /** 配置 JSON 本体 —— 导入解析时本字段必填 */
    private JsonNode configJson;
}
