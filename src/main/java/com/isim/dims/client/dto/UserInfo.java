package com.isim.dims.client.dto;

import lombok.Data;

import java.util.List;

/**
 * IAMS 用户公开信息
 *
 * <p>契约来源：platform-contracts/apis/iams.openapi.yaml → schema UserInfo。
 * 字段为公开信息，不含密码/密钥；与前端口径一致：显示名用
 * {@code display_name}、部门用 {@code department_id}/{@code department_name}
 * （docs/子系统对接指南.md §5.5）。</p>
 *
 * <p>字段名 snake_case 由全局 Jackson {@code property-naming-strategy: SNAKE_CASE}
 * 自动映射（application.yml）。</p>
 *
 * @feature: infra
 */
@Data
public class UserInfo {

    /** 用户唯一 ID（UUID 字符串） */
    private String id;

    /** 用户名 */
    private String username;

    /** 显示名（可为空） */
    private String displayName;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String phone;

    /** 头像地址 */
    private String avatarUrl;

    /** 账号状态：enabled / disabled / locked */
    private String status;

    /** 部门 ID */
    private String departmentId;

    /** 部门名 */
    private String departmentName;

    /** 角色摘要列表 */
    private List<RoleBrief> roles;

    /** 已绑定的第三方 SSO 列表 */
    private List<SsoBindingBrief> ssoBindings;

    /** 创建时间（RFC3339） */
    private String createdAt;

    /**
     * 角色摘要
     */
    @Data
    public static class RoleBrief {
        private String id;
        private String code;
        private String name;
    }

    /**
     * SSO 绑定摘要
     */
    @Data
    public static class SsoBindingBrief {
        private String provider;
        private String externalId;
        private String externalName;
        private String boundAt;
    }
}
