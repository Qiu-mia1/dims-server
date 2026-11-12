package com.isim.dims.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.client.SimsAccessTokenProvider;
import com.isim.dims.client.SimsRemoteException;
import com.isim.dims.client.SimsTagClient;
import com.isim.dims.dto.TagRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SIMS 片段域词表缓存单测。
 *
 * <p>它替代了被移除的本地 {@code tag} 表：切片列表/详情要按 tag_id 取标签名，
 * 而 SIMS 的 {@code /tags?scope=FRAGMENT} **不支持按 id 批量查** —— 若没有这层缓存，
 * 一页 20 条切片就是 N+1 次外部调用。
 */
class SimsTagCatalogTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SimsTagClient client;
    private SimsAccessTokenProvider tokenProvider;
    private SimsTagCatalog catalog;

    @BeforeEach
    void setUp() {
        client = mock(SimsTagClient.class);
        tokenProvider = mock(SimsAccessTokenProvider.class);
        when(tokenProvider.token()).thenReturn("svc-token");
    }

    private SimsTagCatalog catalogWithTtl(long ttlMs) {
        return new SimsTagCatalog(client, tokenProvider, ttlMs);
    }

    private static JsonNode json(String raw) {
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void stubPageOne(String json) {
        when(client.listTags(eq("svc-token"), eq(1), eq(200), isNull(), isNull(), isNull()))
                .thenReturn(json(json));
    }

    // ============ 加载与缓存 ============

    @Test
    void find_loadsOnceThenServesFromCache() {
        stubPageOne("{\"items\":[{\"id\":\"t1\",\"name\":\"高速\",\"categoryPath\":\"自定义/道路\"}],\"total\":1}");
        catalog = catalogWithTtl(60_000);

        TagRef first = catalog.find("t1");
        TagRef second = catalog.find("t1");
        catalog.all();

        assertThat(first.getName()).isEqualTo("高速");
        assertThat(first.getCategoryPath()).isEqualTo("自定义/道路");
        assertThat(second).isEqualTo(first);
        verify(client, times(1)).listTags(eq("svc-token"), eq(1), eq(200), isNull(), isNull(), isNull());
    }

    @Test
    void invalidate_forcesReload() {
        stubPageOne("{\"items\":[{\"id\":\"t1\",\"name\":\"高速\"}],\"total\":1}");
        catalog = catalogWithTtl(60_000);

        catalog.find("t1");
        catalog.invalidate();
        catalog.find("t1");

        verify(client, times(2)).listTags(eq("svc-token"), eq(1), eq(200), isNull(), isNull(), isNull());
    }

    @Test
    void ttlExpired_reloads() throws Exception {
        stubPageOne("{\"items\":[{\"id\":\"t1\",\"name\":\"高速\"}],\"total\":1}");
        catalog = catalogWithTtl(10);

        catalog.find("t1");
        Thread.sleep(40);
        catalog.find("t1");

        verify(client, times(2)).listTags(eq("svc-token"), eq(1), eq(200), isNull(), isNull(), isNull());
    }

    @Test
    void multiPage_accumulatesUntilTotalReached() {
        stubPageOne("{\"items\":[{\"id\":\"t1\",\"name\":\"a\"},{\"id\":\"t2\",\"name\":\"b\"}],\"total\":3}");
        when(client.listTags(eq("svc-token"), eq(2), eq(200), isNull(), isNull(), isNull()))
                .thenReturn(json("{\"items\":[{\"id\":\"t3\",\"name\":\"c\"}],\"total\":3}"));
        catalog = catalogWithTtl(60_000);

        assertThat(catalog.all()).hasSize(3);
    }

    /** 防御空页面空转：total 虚高但下一页 items 为空时必须停下（旧同步实现踩过这个坑）。 */
    @Test
    void multiPage_stopsWhenPageIsEmpty() {
        stubPageOne("{\"items\":[{\"id\":\"t1\",\"name\":\"a\"}],\"total\":99}");
        when(client.listTags(eq("svc-token"), eq(2), eq(200), isNull(), isNull(), isNull()))
                .thenReturn(json("{\"items\":[],\"total\":99}"));
        catalog = catalogWithTtl(60_000);

        assertThat(catalog.all()).hasSize(1);
        verify(client, times(1)).listTags(eq("svc-token"), eq(2), eq(200), isNull(), isNull(), isNull());
    }

    // ============ 名称解析降级 ============

    @Test
    void nameOf_unknownId_returnsPlaceholderWithIdPrefix() {
        stubPageOne("{\"items\":[],\"total\":0}");
        catalog = catalogWithTtl(60_000);

        assertThat(catalog.nameOf("abcdefgh-1234-5678")).isEqualTo("未知标签(abcdefgh)");
        assertThat(catalog.find("abcdefgh-1234-5678")).isNull();
    }

    @Test
    void nameOf_nullOrBlank_returnsDash() {
        stubPageOne("{\"items\":[],\"total\":0}");
        catalog = catalogWithTtl(60_000);

        assertThat(catalog.nameOf(null)).isEqualTo("-");
        assertThat(catalog.nameOf("  ")).isEqualTo("-");
    }

    @Test
    void nameOf_shortId_placeholderUsesWholeId() {
        stubPageOne("{\"items\":[],\"total\":0}");
        catalog = catalogWithTtl(60_000);

        assertThat(catalog.nameOf("abc")).isEqualTo("未知标签(abc)");
    }

    // ============ 拉取失败降级 ============

    @Test
    void loadFailure_keepsPreviousCacheAndDoesNotThrow() {
        stubPageOne("{\"items\":[{\"id\":\"t1\",\"name\":\"高速\"}],\"total\":1}");
        catalog = catalogWithTtl(60_000);
        assertThat(catalog.nameOf("t1")).isEqualTo("高速");

        when(client.listTags(eq("svc-token"), eq(1), eq(200), isNull(), isNull(), isNull()))
                .thenThrow(new SimsRemoteException(502, "SIMS 不可达"));
        catalog.invalidate();

        assertThatCode(() -> catalog.nameOf("t1")).doesNotThrowAnyException();
        assertThat(catalog.nameOf("t1")).isEqualTo("高速");
    }

    @Test
    void loadFailureOnColdStart_returnsPlaceholderInsteadOfThrowing() {
        when(client.listTags(eq("svc-token"), eq(1), eq(200), isNull(), isNull(), isNull()))
                .thenThrow(new SimsRemoteException(502, "SIMS 不可达"));
        catalog = catalogWithTtl(60_000);

        assertThatCode(() -> catalog.nameOf("t1")).doesNotThrowAnyException();
        assertThat(catalog.nameOf("t1")).isEqualTo("未知标签(t1)");
        assertThat(catalog.all()).isEmpty();
    }
}
