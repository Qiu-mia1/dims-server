package com.isim.dims.security;

import java.util.List;

/**
 * DIMS 权限点常量（唯一来源）
 *
 * <p>权限点格式：{子系统简称小写}:{资源}:{操作}（见 docs/子系统对接指南.md §3.2）。
 * 与 {@code docs/dims-iams-permissions.md} 权限点清单一一对应；IAMS 侧预置脚本已并入
 * isim-iams 的 Flyway 迁移（{@code V7__add_dims_permissions.sql} 基础 21 个 +
 * {@code V8__add_dims_delete_permissions.sql} 删除类 3 个）。</p>
 *
 * <p><b>粒度约定（2026-09-16 细化）</b>：{@code write} 仅表示「新增/修改」，
 * <b>不再隐含删除能力</b>；删除类操作一律使用独立权限点 {@link #SLICE_DELETE} /
 * {@link #SLICE_REIMPORT} / {@link #TAG_DELETE}，因此可以单独收回。</p>
 *
 * <p>JWT 的 {@code authorities} claim 中携带这些权限点，Resource Server 本地判定，
 * 不回查 IAMS（指南 §2.3 / §3.2）。</p>
 *
 * @feature: infra
 */
public final class DimsPermission {

    private DimsPermission() {
    }

    // ==================== slice 数据切片 ====================
    /**
     * 切片检索 / 详情查看
     */
    public static final String SLICE_READ = "dims:slice:read";
    /**
     * 上传 / 目录导入（不含删除）
     */
    public static final String SLICE_WRITE = "dims:slice:write";
    /**
     * 删除切片（级联清理原始文件、导出产物与数据库记录）
     */
    public static final String SLICE_DELETE = "dims:slice:delete";
    /**
     * 下载切片产物（原始 bag / OpenX 场景 / CARLA / 日志）。
     *
     * <p>数据外发敏感操作，从 {@link #EXPORT_READ} 中拆出单列：只给「看导出任务」的账户
     * 不应自动拥有把整包 bag 拉到本地的能力，可单独收回。</p>
     */
    public static final String SLICE_DOWNLOAD = "dims:slice:download";
    /**
     * 重新导入切片（物理删除原切片后重新转换，破坏性等同删除）
     */
    public static final String SLICE_REIMPORT = "dims:slice:reimport";

    // ==================== export 数据导出 ====================
    /**
     * 导出任务查询 / 下载
     */
    public static final String EXPORT_READ = "dims:export:read";
    /**
     * 创建导出任务
     */
    public static final String EXPORT_WRITE = "dims:export:write";

    // ==================== replay 2D 回放 ====================
    /**
     * 回放数据读取
     */
    public static final String REPLAY_READ = "dims:replay:read";
    /**
     * 回放提取任务触发
     */
    public static final String REPLAY_WRITE = "dims:replay:write";

    // ==================== scene 场景提取 ====================
    /**
     * 场景提取结果查看
     */
    public static final String SCENE_READ = "dims:scene:read";
    /**
     * 场景提取任务触发
     */
    public static final String SCENE_WRITE = "dims:scene:write";

    // ==================== rule 场景规则 ====================
    /**
     * 场景规则查看
     */
    public static final String RULE_READ = "dims:rule:read";
    /**
     * 场景规则管理（增改/启停/优先级/阈值配置）
     */
    public static final String RULE_WRITE = "dims:rule:write";

    // ==================== tag 标签 ====================
    /**
     * 标签查看
     */
    public static final String TAG_READ = "dims:tag:read";
    /**
     * 标签新建 / 改名 / 切片打标（不含删除）
     */
    public static final String TAG_WRITE = "dims:tag:write";
    /**
     * 删除标签（自动解绑所有切片关联）
     */
    public static final String TAG_DELETE = "dims:tag:delete";

    // ==================== task 任务 ====================
    /**
     * 导入任务进度查询
     */
    public static final String TASK_READ = "dims:task:read";

    // ==================== dashboard 用户个性化配置方案 ====================
    // 注：code 沿用 dashboard 前缀（历史命名，IAMS V7 建点时的业务叫「驾驶舱」）。
    // 现已更名为「配置方案查看 / 配置方案管理」（IAMS Flyway V9），code 保持不变 ——
    // 它同时是 JWT authorities 的内容与 @RequireDashboardRead/Write 的表达式。
    /** 用户个性化配置方案查看与导出 */
    // ==================== dashboard 驾驶舱 ====================
    /**
     * 驾驶舱 / 仪表盘查看
     */
    public static final String DASHBOARD_READ = "dims:dashboard:read";
    /** 用户个性化配置方案新建/编辑/删除/导入 */
    /**
     * 驾驶舱统计提取触发
     */
    public static final String DASHBOARD_WRITE = "dims:dashboard:write";

    // ==================== calibration 传感器标定 ====================
    /**
     * 标定参数查看
     */
    public static final String CALIBRATION_READ = "dims:calibration:read";
    /**
     * 标定参数录入
     */
    public static final String CALIBRATION_WRITE = "dims:calibration:write";

    // ==================== trajectory GPS 轨迹 ====================
    /**
     * 轨迹查看
     */
    public static final String TRAJECTORY_READ = "dims:trajectory:read";
    /**
     * 轨迹提取触发
     */
    public static final String TRAJECTORY_WRITE = "dims:trajectory:write";

    // ==================== visualization 可视化 ====================
    /**
     * 可视化数据读取（视频流列表/信号数据/提取任务查询）
     */
    public static final String VISUALIZATION_READ = "dims:visualization:read";
    /**
     * 可视化数据提取触发
     */
    public static final String VISUALIZATION_WRITE = "dims:visualization:write";

    // ==================== mxf MXF 埋点数据 ====================
    /**
     * MXF 看板（KPI / 事件分布 / 特殊事件通知）
     */
    public static final String MXF_READ = "dims:mxf:read";
    /**
     * MXF 数据包上传解析、删除
     */
    public static final String MXF_WRITE = "dims:mxf:write";

    /**
     * 全部权限点（与 isim-iams 侧 Flyway V7 / V8 预置脚本、docs/dims-iams-permissions.md 一一对应）。
     * 仅供调试/测试场景构造「超级权限身份」使用，不得用于业务代码。
     */
    public static final List<String> ALL = List.of(
            SLICE_READ, SLICE_WRITE, SLICE_DELETE, SLICE_REIMPORT, SLICE_DOWNLOAD,
            EXPORT_READ, EXPORT_WRITE,
            REPLAY_READ, REPLAY_WRITE,
            SCENE_READ, SCENE_WRITE,
            RULE_READ, RULE_WRITE,
            TAG_READ, TAG_WRITE, TAG_DELETE,
            TASK_READ,
            DASHBOARD_READ, DASHBOARD_WRITE,
            CALIBRATION_READ, CALIBRATION_WRITE,
            TRAJECTORY_READ, TRAJECTORY_WRITE,
            VISUALIZATION_READ, VISUALIZATION_WRITE,
            MXF_READ, MXF_WRITE
    );

    /**
     * 删除类权限点：普通账户若不分配这些权限点，即无法删除/重建任何数据。
     */
    public static final List<String> DELETE_CLASS = List.of(SLICE_DELETE, SLICE_REIMPORT, TAG_DELETE);
}
