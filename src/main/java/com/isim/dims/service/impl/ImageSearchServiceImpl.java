package com.isim.dims.service.impl;

import com.isim.dims.client.SigLIPClient;
import com.isim.dims.entity.DataSlice;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.mapper.ImageAssetMapper;
import com.isim.dims.service.ImageSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 帧图语义检索服务实现
 *
 * <p>检索基于数据库端 pgvector（image_asset 表）：查询向量经 SigLIP（文/图/特征）
 * 计算后，在数据库内做余弦相似度 Top-N 排序，不再全库扫文件。每张帧图的
 * 768 维 embedding 已由 extract_video_segment.py 在保存帧图后落库。</p>
 *
 * <p>未启用 siglip 时抛 FEATURES_DISABLED。</p>
 * @feature: visualization
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageSearchServiceImpl implements ImageSearchService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 50;

    private final SigLIPClient siglipClient;
    private final DataSliceMapper dataSliceMapper;
    private final ImageAssetMapper imageAssetMapper;

    @Override
    public List<Map<String, Object>> searchByText(String text, Integer limit) {
        requireEnabled();
        double[] query = siglipClient.embedText(text);
        return search(query, limit);
    }

    @Override
    public List<Map<String, Object>> searchByImage(byte[] imageBytes, Integer limit) {
        requireEnabled();
        if (imageBytes == null || imageBytes.length == 0) {
            throw new RuntimeException("VALIDATION_FAILED: 图片内容为空");
        }
        double[] query = siglipClient.embedImage(imageBytes);
        return search(query, limit);
    }

    @Override
    public List<Map<String, Object>> searchByFeature(double[] feature, Integer limit) {
        requireEnabled();
        if (feature == null || feature.length == 0) {
            throw new RuntimeException("VALIDATION_FAILED: feature 向量为空");
        }
        // 必须是 SigLIP 空间的 embedding，否则余弦相似度无意义
        int embedDims = siglipClient.embedDims();
        if (feature.length != embedDims) {
            throw new RuntimeException(String.format(
                    "VALIDATION_FAILED: feature 维度不匹配，期望 %d 维（SigLIP embedding），实际 %d 维。"
                            + "注意：此处只接受 SigLIP 模型产出的 embedding，不接受数据包内的感知特征向量",
                    embedDims, feature.length));
        }
        return search(feature, limit);
    }

    private void requireEnabled() {
        if (!siglipClient.isEnabled()) {
            throw new RuntimeException(
                    "FEATURES_DISABLED: 未启用图像语义检索，请配置 dims.siglip.enabled=true 并部署 embedding 服务");
        }
    }

    private List<Map<String, Object>> search(double[] query, Integer limit) {
        int requested = limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
        int cap = Math.min(requested, MAX_LIMIT);
        // 直接走数据库 pgvector 余弦相似度 Top-N，不再扫描文件系统
        List<Map<String, Object>> rows = imageAssetMapper.searchByEmbedding(toVectorLiteral(query), cap);
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String sliceId = str(row.get("slice_id"));
            String topicSafe = str(row.get("topic_safe"));
            String fileName = str(row.get("file_name"));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("slice_id", sliceId);
            entry.put("topic", str(row.get("topic")));
            entry.put("topic_safe", topicSafe);
            entry.put("timestamp", row.get("ts"));
            entry.put("file_name", fileName);
            entry.put("score", toScore(row.get("score")));
            entry.put("url", "/v1/data-slices/" + sliceId + "/video-images/" + topicSafe + "/" + fileName);
            enrichSlice(entry, sliceId);
            result.add(entry);
        }
        return result;
    }

    private void enrichSlice(Map<String, Object> row, String sliceId) {
        try {
            DataSlice slice = dataSliceMapper.selectById(sliceId);
            row.put("slice_name", slice == null ? null : slice.getName());
            row.put("slice_status", slice == null ? null : slice.getStatus());
            row.put("slice_vin", slice == null ? null : slice.getVin());
        } catch (Exception e) {
            log.debug("Failed to load slice metadata for search result: sliceId={}", sliceId, e);
        }
    }

    /** 把查询向量转成 pgvector 字面量，如 "[0.1,0.2,...]" */
    private static String toVectorLiteral(double[] query) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < query.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(query[i]);
        }
        return sb.append(']').toString();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /** 数据库返回的 score 可能是 numeric/float，统一转 double */
    private static double toScore(Object o) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        return 0.0;
    }
}