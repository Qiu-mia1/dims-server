package com.isim.dims.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.isim.dims.dto.UserDashboardConfigDetailResponse;
import com.isim.dims.dto.UserDashboardConfigExportFile;
import com.isim.dims.dto.UserDashboardConfigListResponse;
import com.isim.dims.dto.UserDashboardConfigSummaryResponse;
import com.isim.dims.entity.UserDashboardConfig;
import com.isim.dims.mapper.UserDashboardConfigMapper;
import com.isim.dims.service.impl.UserDashboardConfigServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户个性化配置方案 Service 测试
 *
 * <p>覆盖契约要点：列表按 user_id 分页且摘要不含 config_json、新建名重复 → NAME_CONFLICT、
 * 归属校验失败 → NOT_FOUND、编辑为全量替换（description 传 null 即清空）、
 * updated_at 显式刷新、name/description 长度与空白校验，
 * 以及导入导出的派生规则（导出包装字段完整、导入以文件名为方案名、导入重名 → NAME_CONFLICT、
 * 导入文件非法 → IMPORT_INVALID）。</p>
 *
 * <p>⚠️ MyBatis-Plus 3.5.9 的 {@code BaseMapper} 存在重载
 * （{@code insert(T)}/{@code insert(Collection)}、{@code updateById(T)}/{@code updateById(Collection)}），
 * 故 {@code insert}/{@code updateById} 的匹配器<b>必须带类型</b> {@code any(实体.class)}，
 * 裸 {@code any()} 会编译报「对insert的引用不明确」。</p>
 *
 * <p>⚠️ 分页用手写 {@code selectCount} + {@code selectList}（3.5.9 已移除
 * {@code PaginationInnerInterceptor}），故这里<b>不 mock {@code selectPage}</b>。</p>
 *
 * @feature: replay
 */
@ExtendWith(MockitoExtension.class)
class UserDashboardConfigServiceImplTest {

    private static final String USER_ID = "00000000-0000-0000-0000-000000000010";
    private static final String OTHER_USER_ID = "00000000-0000-0000-0000-000000000099";
    private static final String CONFIG_ID = "cfg-1";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 模拟 Web 层注入的全局 ObjectMapper（{@code property-naming-strategy: SNAKE_CASE}）。
     * 测试里手建实例时必须显式设置命名策略，否则字段会序列化成驼峰。
     */
    private static final ObjectMapper SNAKE_JSON =
            new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

    @Mock
    private UserDashboardConfigMapper userDashboardConfigMapper;

    @InjectMocks
    private UserDashboardConfigServiceImpl service;

    static {
        // 纯单元测试无 MyBatis 上下文：手动注册 TableInfo，
        // 使 LambdaQueryWrapper 的列名解析（getSqlSegment / getParamNameValuePairs）可用。
        // 否则 getSqlSegment() 抛
        // "MybatisPlus can not find lambda cache for this entity"。同 ExportServiceImplTest。
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserDashboardConfig.class);
    }

    // ============ 列表 ============

    @Test
    void list_returnsSummaryWithoutConfigJson() {
        when(userDashboardConfigMapper.selectCount(any())).thenReturn(2L);
        when(userDashboardConfigMapper.selectList(any()))
                .thenReturn(List.of(row("cfg-1", "方案A", "泊车关注项"), row("cfg-2", "方案B", null)));

        UserDashboardConfigListResponse resp = service.list(USER_ID, 1, 20);

        assertThat(resp.getTotal()).isEqualTo(2);
        assertThat(resp.getPage()).isEqualTo(1);
        assertThat(resp.getPageSize()).isEqualTo(20);
        assertThat(resp.getItems()).hasSize(2);

        // 列表项是摘要 DTO——结构上就不含 config_json
        UserDashboardConfigSummaryResponse first = resp.getItems().get(0);
        assertThat(first).isInstanceOf(UserDashboardConfigSummaryResponse.class);
        assertThat(first.getConfigId()).isEqualTo("cfg-1");
        assertThat(first.getName()).isEqualTo("方案A");
        assertThat(first.getDescription()).isEqualTo("泊车关注项");
        assertThat(resp.getItems().get(1).getDescription()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void list_appliesLimitOffsetForRequestedPage() {
        when(userDashboardConfigMapper.selectCount(any())).thenReturn(100L);
        when(userDashboardConfigMapper.selectList(any())).thenReturn(List.of());

        service.list(USER_ID, 3, 20);

        ArgumentCaptor<Wrapper> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(userDashboardConfigMapper).selectList(captor.capture());
        // page=3, pageSize=20 → offset=(3-1)*20=40；`last(...)` 片段由 getSqlSegment() 带出
        assertThat(captor.getValue().getSqlSegment()).contains("LIMIT 20 OFFSET 40");
    }

    @Test
    void list_empty_returnsZeroTotalAndEmptyItems() {
        when(userDashboardConfigMapper.selectCount(any())).thenReturn(0L);
        when(userDashboardConfigMapper.selectList(any())).thenReturn(List.of());

        UserDashboardConfigListResponse resp = service.list(USER_ID, 1, 20);

        assertThat(resp.getTotal()).isZero();
        assertThat(resp.getItems()).isEmpty();
    }

    @Test
    void list_blankUserId_throwsInvalid() {
        assertThatThrownBy(() -> service.list("   ", 1, 20))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_INVALID:");
        verify(userDashboardConfigMapper, never()).selectCount(any());
    }

    // ============ 新建 ============

    @Test
    void create_success_persistsAndReturnsDetail() {
        // 模拟生产环境：MyBatis-Plus 在 insert 时分配 ASSIGN_UUID 主键并由 insertFill 填充时间戳
        stubInsertAssigningKey("cfg-new");

        UserDashboardConfigDetailResponse resp =
                service.create(USER_ID, "方案A", "泊车关注项", json("{\"panels\":[\"obstacles\"]}"));

        assertThat(resp.getConfigId()).isEqualTo("cfg-new");
        assertThat(resp.getUserId()).isEqualTo(USER_ID);
        assertThat(resp.getName()).isEqualTo("方案A");
        assertThat(resp.getDescription()).isEqualTo("泊车关注项");
        assertThat(resp.getConfigJson().get("panels").get(0).asText()).isEqualTo("obstacles");
        assertThat(resp.getCreatedAt()).startsWith("2026-09-16T01:00:00");

        ArgumentCaptor<UserDashboardConfig> captor = ArgumentCaptor.forClass(UserDashboardConfig.class);
        verify(userDashboardConfigMapper).insert(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
        assertThat(captor.getValue().getConfigJson()).isEqualTo("{\"panels\":[\"obstacles\"]}");
    }

    @Test
    void create_trimsName() {
        when(userDashboardConfigMapper.insert(any(UserDashboardConfig.class))).thenReturn(1);

        service.create(USER_ID, "  方案A  ", null, json("{}"));

        ArgumentCaptor<UserDashboardConfig> captor = ArgumentCaptor.forClass(UserDashboardConfig.class);
        verify(userDashboardConfigMapper).insert(captor.capture());
        // trim 后入库，避免 " 方案A" 与 "方案A" 被唯一约束当作两个方案
        assertThat(captor.getValue().getName()).isEqualTo("方案A");
    }

    @Test
    void create_nameBlank_throwsInvalid() {
        assertThatThrownBy(() -> service.create(USER_ID, "   ", null, json("{}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_INVALID:");
        verify(userDashboardConfigMapper, never()).insert(any(UserDashboardConfig.class));
    }

    @Test
    void create_nameTooLong_throwsInvalid() {
        assertThatThrownBy(() -> service.create(USER_ID, "a".repeat(121), null, json("{}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_INVALID:");
    }

    @Test
    void create_nullConfigJson_throwsInvalid() {
        assertThatThrownBy(() -> service.create(USER_ID, "方案A", null, null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_INVALID:");
        verify(userDashboardConfigMapper, never()).insert(any(UserDashboardConfig.class));
    }

    @Test
    void create_descriptionTooLong_throwsInvalid() {
        assertThatThrownBy(() -> service.create(USER_ID, "方案A", "d".repeat(501), json("{}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_INVALID:");
    }

    @Test
    void create_blankDescription_normalizesToNull() {
        when(userDashboardConfigMapper.insert(any(UserDashboardConfig.class))).thenReturn(1);

        service.create(USER_ID, "方案A", "   ", json("{}"));

        ArgumentCaptor<UserDashboardConfig> captor = ArgumentCaptor.forClass(UserDashboardConfig.class);
        verify(userDashboardConfigMapper).insert(captor.capture());
        // 空白描述归一为 NULL，避免 "" 与 NULL 两种「空」并存
        assertThat(captor.getValue().getDescription()).isNull();
    }

    @Test
    void create_nameConflict_throwsNameConflict() {
        when(userDashboardConfigMapper.insert(any(UserDashboardConfig.class)))
                .thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.create(USER_ID, "方案A", null, json("{}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_NAME_CONFLICT:");
    }

    // ============ 详情 ============

    @Test
    void getDetail_success_returnsParsedJson() {
        when(userDashboardConfigMapper.selectOne(any()))
                .thenReturn(row(CONFIG_ID, "方案A", "泊车关注项"));

        UserDashboardConfigDetailResponse resp = service.getDetail(USER_ID, CONFIG_ID);

        assertThat(resp.getConfigId()).isEqualTo(CONFIG_ID);
        assertThat(resp.getName()).isEqualTo("方案A");
        assertThat(resp.getConfigJson().get("panels").get(0).asText()).isEqualTo("obstacles");
        assertThat(resp.getUpdatedAt()).startsWith("2026-09-15T06:43:53");
    }

    @Test
    void getDetail_notFound_throwsNotFound() {
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(null);

        // 不存在 与 属于他人 都表现为 NOT_FOUND——归属校验下沉到 SQL，不泄露存在性
        assertThatThrownBy(() -> service.getDetail(OTHER_USER_ID, CONFIG_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_NOT_FOUND:");
    }

    @Test
    void getDetail_blankConfigId_throwsInvalid() {
        assertThatThrownBy(() -> service.getDetail(USER_ID, "  "))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_INVALID:");
        verify(userDashboardConfigMapper, never()).selectOne(any());
    }

    // ============ 编辑（全量替换） ============

    @Test
    void update_success_refreshesUpdatedAtAndReplacesFields() {
        LocalDateTime stale = LocalDateTime.of(2020, 1, 1, 0, 0, 0);
        UserDashboardConfig existing = row(CONFIG_ID, "旧名", "旧描述");
        existing.setUpdatedAt(stale);
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(existing);

        UserDashboardConfigDetailResponse resp =
                service.update(USER_ID, CONFIG_ID, "新名", "新描述", json("{\"panels\":[\"raw\"]}"));

        ArgumentCaptor<UserDashboardConfig> captor = ArgumentCaptor.forClass(UserDashboardConfig.class);
        verify(userDashboardConfigMapper).updateById(captor.capture());
        UserDashboardConfig updated = captor.getValue();

        assertThat(updated.getId()).isEqualTo(CONFIG_ID);
        assertThat(updated.getName()).isEqualTo("新名");
        assertThat(updated.getDescription()).isEqualTo("新描述");
        assertThat(updated.getConfigJson()).isEqualTo("{\"panels\":[\"raw\"]}");
        // 先查后改路径必须显式刷新 updated_at（strictUpdateFill 仅在字段为 null 时填充）
        assertThat(updated.getUpdatedAt()).isAfter(stale);
        assertThat(resp.getName()).isEqualTo("新名");
    }

    @Test
    void update_nullDescription_clearsIt() {
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(row(CONFIG_ID, "方案A", "旧描述"));

        service.update(USER_ID, CONFIG_ID, "方案A", null, json("{}"));

        ArgumentCaptor<UserDashboardConfig> captor = ArgumentCaptor.forClass(UserDashboardConfig.class);
        verify(userDashboardConfigMapper).updateById(captor.capture());
        // 全量替换语义：传 null 即清空。
        // 实体上 updateStrategy = FieldStrategy.ALWAYS 保证这真的会生成 SET description = NULL。
        assertThat(captor.getValue().getDescription()).isNull();
    }

    @Test
    void update_blankDescription_normalizesToNull() {
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(row(CONFIG_ID, "方案A", "旧描述"));

        service.update(USER_ID, CONFIG_ID, "方案A", "   ", json("{}"));

        ArgumentCaptor<UserDashboardConfig> captor = ArgumentCaptor.forClass(UserDashboardConfig.class);
        verify(userDashboardConfigMapper).updateById(captor.capture());
        assertThat(captor.getValue().getDescription()).isNull();
    }

    @Test
    void update_notFound_throwsNotFound() {
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.update(USER_ID, CONFIG_ID, "方案A", null, json("{}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_NOT_FOUND:");
        verify(userDashboardConfigMapper, never()).updateById(any(UserDashboardConfig.class));
    }

    @Test
    void update_renameConflict_throwsNameConflict() {
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(row(CONFIG_ID, "方案A", null));
        when(userDashboardConfigMapper.updateById(any(UserDashboardConfig.class)))
                .thenThrow(new DuplicateKeyException("dup"));

        assertThatThrownBy(() -> service.update(USER_ID, CONFIG_ID, "方案B", null, json("{}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_NAME_CONFLICT:");
    }

    @Test
    void update_nameBlank_throwsInvalid() {
        assertThatThrownBy(() -> service.update(USER_ID, CONFIG_ID, "  ", null, json("{}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_INVALID:");
        verify(userDashboardConfigMapper, never()).selectOne(any());
    }

    // ============ 删除 ============

    @Test
    void delete_success() {
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(row(CONFIG_ID, "方案A", null));

        service.delete(USER_ID, CONFIG_ID);

        verify(userDashboardConfigMapper).deleteById(anyString());
    }

    @Test
    void delete_notFound_throwsNotFound() {
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.delete(USER_ID, CONFIG_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_NOT_FOUND:");
        verify(userDashboardConfigMapper, never()).deleteById(anyString());
    }

    // ============ 导出 ============

    @Test
    void export_returnsSelfDescribingWrapper() {
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(row(CONFIG_ID, "方案A", "泊车关注项"));

        UserDashboardConfigExportFile file = service.export(USER_ID, CONFIG_ID);

        assertThat(file.getVersion()).isEqualTo(UserDashboardConfigExportFile.CURRENT_VERSION);
        assertThat(file.getExportedAt()).startsWith("20").isNotBlank();
        assertThat(file.getName()).isEqualTo("方案A");
        // description 必须随文件带出——否则同事导入后拿到的是「半个方案」
        assertThat(file.getDescription()).isEqualTo("泊车关注项");
        assertThat(file.getConfigJson().get("panels").get(0).asText()).isEqualTo("obstacles");
    }

    @Test
    void export_notFound_throwsNotFound() {
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.export(USER_ID, CONFIG_ID))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_NOT_FOUND:");
    }

    @Test
    void export_blankConfigId_throwsInvalid() {
        assertThatThrownBy(() -> service.export(USER_ID, "  "))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_INVALID:");
        verify(userDashboardConfigMapper, never()).selectOne(any());
    }

    @Test
    void export_corruptStoredJson_degradesToNullInsteadOfFailing() {
        UserDashboardConfig corrupt = row(CONFIG_ID, "方案A", null);
        corrupt.setConfigJson("{ not json");
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(corrupt);

        UserDashboardConfigExportFile file = service.export(USER_ID, CONFIG_ID);

        // 单条脏数据不应让导出整体失败（与详情接口的退化口径一致）
        assertThat(file.getConfigJson()).isNull();
        assertThat(file.getName()).isEqualTo("方案A");
    }

    // ============ 导入 ============

    @Test
    void import_success_derivesNameFromFileName() {
        stubInsertAssigningKey("cfg-imported");

        UserDashboardConfigDetailResponse resp = service.importConfig(
                USER_ID, "泊车关注方案.json", exportBytes("文件内名称", "泊车关注项", "{\"panels\":[\"obstacles\"]}"));

        // 用户要求：默认把文件名称当作配置名称 —— 文件名优先于文件内 name 字段
        assertThat(resp.getName()).isEqualTo("泊车关注方案");
        assertThat(resp.getDescription()).isEqualTo("泊车关注项");
        assertThat(resp.getConfigJson().get("panels").get(0).asText()).isEqualTo("obstacles");
        assertThat(resp.getConfigId()).isEqualTo("cfg-imported");

        ArgumentCaptor<UserDashboardConfig> captor = ArgumentCaptor.forClass(UserDashboardConfig.class);
        verify(userDashboardConfigMapper).insert(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("泊车关注方案");
    }

    @Test
    void import_stripsPathPrefixExtensionAndSurroundingWhitespace() {
        when(userDashboardConfigMapper.insert(any(UserDashboardConfig.class))).thenReturn(1);

        // Windows 完整路径
        assertThat(service.importConfig(USER_ID, "C:\\Users\\me\\Downloads\\方案A.json", exportBytes())
                .getName()).isEqualTo("方案A");
        // POSIX 完整路径
        assertThat(service.importConfig(USER_ID, "/tmp/方案B.json", exportBytes())
                .getName()).isEqualTo("方案B");
        // 扩展名大小写不敏感
        assertThat(service.importConfig(USER_ID, "方案C.JSON", exportBytes())
                .getName()).isEqualTo("方案C");
        // 文件名前后空白：必须先 trim 再剥扩展名，否则会得到 "方案D.json"
        assertThat(service.importConfig(USER_ID, "  方案D.json  ", exportBytes())
                .getName()).isEqualTo("方案D");
        // 扩展名前带空格
        assertThat(service.importConfig(USER_ID, "方案E .json", exportBytes())
                .getName()).isEqualTo("方案E");
    }

    @Test
    void import_fileNameBlank_fallsBackToNameInsideFile() {
        stubInsertAssigningKey("cfg-fallback");

        // 极少数客户端不上传 filename（空串）时才回落文件内 name
        UserDashboardConfigDetailResponse resp =
                service.importConfig(USER_ID, "   ", exportBytes("文件内名称", null, "{}"));

        assertThat(resp.getName()).isEqualTo("文件内名称");
    }

    @Test
    void import_fileNameAndContentNameBothBlank_throwsImportInvalid() {
        assertThatThrownBy(() -> service.importConfig(USER_ID, null, exportBytes(null, null, "{}")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_IMPORT_INVALID:");
        verify(userDashboardConfigMapper, never()).insert(any(UserDashboardConfig.class));
    }

    @Test
    void import_fileNameTooLong_throwsImportInvalidInsteadOfTruncating() {
        // 截断会静默改变用户看到的方案名，故报错让用户改名
        String longFileName = "长".repeat(121) + ".json";

        assertThatThrownBy(() -> service.importConfig(USER_ID, longFileName, exportBytes()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_IMPORT_INVALID:");
    }

    @Test
    void import_nameConflict_throwsNameConflict() {
        when(userDashboardConfigMapper.insert(any(UserDashboardConfig.class)))
                .thenThrow(new DuplicateKeyException("dup"));

        // 重名不做自动改名（如「方案A(1)」），直接 409 让用户改文件名
        assertThatThrownBy(() -> service.importConfig(USER_ID, "方案A.json", exportBytes()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_NAME_CONFLICT:");
    }

    @Test
    void import_emptyFile_throwsImportInvalid() {
        assertThatThrownBy(() -> service.importConfig(USER_ID, "方案A.json", new byte[0]))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_IMPORT_INVALID:");
        assertThatThrownBy(() -> service.importConfig(USER_ID, "方案A.json", null))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_IMPORT_INVALID:");
    }

    @Test
    void import_notValidJson_throwsImportInvalid() {
        assertThatThrownBy(() -> service.importConfig(
                USER_ID, "方案A.json", "this is not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_IMPORT_INVALID:")
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void import_rootIsNotObject_throwsImportInvalid() {
        assertThatThrownBy(() -> service.importConfig(
                USER_ID, "方案A.json", "[1,2,3]".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_IMPORT_INVALID:")
                .hasMessageContaining("JSON object");
    }

    @Test
    void import_missingConfigJsonKey_throwsImportInvalid() {
        // 刻意不把「裸配置 JSON」当合法输入：宁可明确报错，也不静默猜测用户意图
        byte[] bare = "{\"panels\":[\"obstacles\"]}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.importConfig(USER_ID, "方案A.json", bare))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_IMPORT_INVALID:")
                .hasMessageContaining("config_json");
    }

    @Test
    void import_configJsonExplicitNull_throwsImportInvalid() {
        byte[] nullConfig = "{\"name\":\"X\",\"config_json\":null}".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> service.importConfig(USER_ID, "方案A.json", nullConfig))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_IMPORT_INVALID:");
    }

    @Test
    void import_utf8Bom_prefixedFile_isAccepted() {
        stubInsertAssigningKey("cfg-bom");

        // 部分编辑器（尤其 Windows 记事本）会在 UTF-8 文件头加 BOM，
        // Jackson 能识别并跳过，这里固化该行为避免回归
        byte[] body = exportBytes("BOM方案", "带BOM", "{}");
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        UserDashboardConfigDetailResponse resp = service.importConfig(USER_ID, "BOM方案.json", withBom);

        assertThat(resp.getName()).isEqualTo("BOM方案");
    }

    @Test
    void import_configJsonArray_isAccepted() {
        stubInsertAssigningKey("cfg-array");

        // config_json 是数组同样合法——后端不解析配置内容语义，只要求该字段存在
        UserDashboardConfigDetailResponse resp =
                service.importConfig(USER_ID, "数组方案.json", exportBytes(null, null, "[{\"p\":1}]"));

        assertThat(resp.getConfigJson().isArray()).isTrue();
    }

    @Test
    void exportThenImport_roundTripPreservesPayload() {
        // 端到端闭环：导出 → （同事拿到文件）→ 导入
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(row(CONFIG_ID, "方案A", "泊车关注项"));
        UserDashboardConfigExportFile exported = service.export(USER_ID, CONFIG_ID);

        // 模拟 Web 层用全局 ObjectMapper（SNAKE_CASE）序列化落盘
        byte[] fileBytes = writeAsSnakeCase(exported);

        stubInsertAssigningKey("cfg-copy");
        UserDashboardConfigDetailResponse imported = service.importConfig(USER_ID, "方案A-副本.json", fileBytes);

        assertThat(imported.getName()).isEqualTo("方案A-副本");
        assertThat(imported.getDescription()).isEqualTo("泊车关注项");
        assertThat(imported.getConfigJson()).isEqualTo(exported.getConfigJson());
    }

    @Test
    void import_fileSerializedWithCamelCase_isRejected() {
        // 固化文件格式契约：导出必须用全局 SNAKE_CASE ObjectMapper。
        // 若 Controller 里改用 new ObjectMapper()，config_json 会写成 configJson，
        // 录下来的这条失败模式能让回归在单测阶段就暴露，而不是等用户反馈「导入报错」。
        when(userDashboardConfigMapper.selectOne(any())).thenReturn(row(CONFIG_ID, "方案A", null));
        UserDashboardConfigExportFile exported = service.export(USER_ID, CONFIG_ID);

        byte[] camelCaseBytes;
        try {
            camelCaseBytes = JSON.writeValueAsBytes(exported);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        assertThat(new String(camelCaseBytes, StandardCharsets.UTF_8)).contains("configJson");

        assertThatThrownBy(() -> service.importConfig(USER_ID, "方案A.json", camelCaseBytes))
                .isInstanceOf(RuntimeException.class)
                .hasMessageStartingWith("USER_CONFIG_IMPORT_INVALID:")
                .hasMessageContaining("config_json");
    }

    // ============ 辅助 ============

    /** 模拟生产环境：MyBatis-Plus 在 insert 时分配 ASSIGN_UUID 主键并填充时间戳 */
    private void stubInsertAssigningKey(String newId) {
        doAnswer(inv -> {
            UserDashboardConfig r = inv.getArgument(0, UserDashboardConfig.class);
            r.setId(newId);
            r.setCreatedAt(LocalDateTime.of(2026, 9, 16, 1, 0, 0));
            r.setUpdatedAt(LocalDateTime.of(2026, 9, 16, 1, 0, 0));
            return 1;
        }).when(userDashboardConfigMapper).insert(any(UserDashboardConfig.class));
    }

    private JsonNode json(String raw) {
        try {
            return JSON.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("bad test json: " + raw, e);
        }
    }

    /** 用 SNAKE_CASE 策略序列化，模拟 Web 层的全局 ObjectMapper */
    private byte[] writeAsSnakeCase(Object value) {
        try {
            return SNAKE_JSON.writeValueAsBytes(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 构造一份与真实导出一致的文件字节（字段名遵循 SNAKE_CASE） */
    private byte[] exportBytes(String name, String description, String configJson) {
        String body = "{\"version\":\"1.0\",\"exported_at\":\"2026-09-17T02:00:00.000Z\","
                + "\"name\":" + literal(name) + ","
                + "\"description\":" + literal(description) + ","
                + "\"config_json\":" + configJson + "}";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /** 只带 config_json / name 的导出文件（其余字段用单个默认值） */
    private byte[] exportBytes() {
        return exportBytes("文件内名称", null, "{\"panels\":[\"obstacles\"]}");
    }

    private String literal(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private UserDashboardConfig row(String id, String name, String description) {
        UserDashboardConfig r = new UserDashboardConfig();
        r.setId(id);
        r.setUserId(USER_ID);
        r.setName(name);
        r.setDescription(description);
        r.setConfigJson("{\"panels\":[\"obstacles\"]}");
        r.setCreatedAt(LocalDateTime.of(2026, 9, 15, 6, 43, 53));
        r.setUpdatedAt(LocalDateTime.of(2026, 9, 15, 6, 43, 53));
        return r;
    }
}
