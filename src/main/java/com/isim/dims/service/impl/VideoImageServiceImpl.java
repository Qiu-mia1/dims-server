package com.isim.dims.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.isim.dims.exception.StorageException;
import com.isim.dims.service.VideoImageService;
import com.isim.dims.util.StorageKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 视频帧图浏览服务实现
 *
 * <p>帧图产物存储在 images/{sliceId}/{topicSafe}/ 下，每张图 frame_<ns>.jpg，
 * 每个 topic 一份 index.json：{ slice_id, topic, topic_safe, images:[{timestamp, file_name}] }。</p>
 * @feature: visualization
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VideoImageServiceImpl implements VideoImageService {

    private static final String IMAGES_ROOT = "images/";

    private final StorageAccess storageAccess;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<Map<String, Object>> listSliceImages(String sliceId) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : storageAccess.list(StorageKeys.videoImagesPrefix(sliceId))) {
            if (!key.endsWith("/index.json")) {
                continue;
            }
            populateImages(result, sliceId, key, true);
        }
        return result;
    }

    @Override
    public InputStream openImage(String sliceId, String topicSafe, String fileName) {
        String key = StorageKeys.videoImagesTopicDir(sliceId, topicSafe) + fileName;
        return storageAccess.open(key);
    }

    @Override
    public List<Map<String, Object>> scanAllImages() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String key : storageAccess.list(IMAGES_ROOT)) {
            if (!key.endsWith("/index.json")) {
                continue;
            }
            // key = images/{sliceId}/{topicSafe}/index.json
            String[] parts = key.split("/");
            if (parts.length < 4) {
                continue;
            }
            String sliceId = parts[1];
            populateImages(result, sliceId, key);
        }
        return result;
    }

    private void populateImages(List<Map<String, Object>> target, String sliceId, String indexKey) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> idx = objectMapper.readValue(storageAccess.readString(indexKey), Map.class);
            String topic = idx.get("topic") == null ? "" : idx.get("topic").toString();
            String topicSafe = idx.get("topic_safe") == null ? "" : idx.get("topic_safe").toString();
            Object imagesRaw = idx.get("images");
            if (!(imagesRaw instanceof List<?> images)) {
                return;
            }
            for (Object o : images) {
                if (!(o instanceof Map<?, ?> m)) {
                    continue;
                }
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("slice_id", sliceId);
                entry.put("topic", topic);
                entry.put("topic_safe", topicSafe);
                entry.put("timestamp", m.get("timestamp"));
                entry.put("file_name", m.get("file_name"));
                target.add(entry);
            }
        } catch (Exception e) {
            log.warn("Failed to parse video images index: key={}", indexKey, e);
        }
    }

    private void populateImages(List<Map<String, Object>> target, String sliceId, String indexKey,
                                boolean withUrl) {
        int before = target.size();
        populateImages(target, sliceId, indexKey);
        if (withUrl) {
            for (int i = before; i < target.size(); i++) {
                Map<String, Object> entry = target.get(i);
                String topicSafe = entry.get("topic_safe") == null ? "" : entry.get("topic_safe").toString();
                String fileName = entry.get("file_name") == null ? "" : entry.get("file_name").toString();
                entry.put("url", "/v1/data-slices/" + sliceId + "/video-images/" + topicSafe + "/" + fileName);
            }
        }
    }
}