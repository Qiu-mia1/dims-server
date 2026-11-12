package com.isim.dims.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.isim.dims.dto.MapFrameResponse;
import com.isim.dims.dto.ObstacleQueryResult;
import com.isim.dims.dto.PerceptionFrameResponse;
import com.isim.dims.dto.RawMessagePageResponse;
import com.isim.dims.dto.ReplayContextResponse;
import com.isim.dims.dto.ReplayFrameQueryResult;

import java.util.List;

/**
 * 2D 回放 Service（read-through 缓存编排）
 *
 * <p>职责（docs/REPLAY-2D-DESIGN.md §5.2 / PLAN §8）：
 * 读缓存 → 未命中触发/复用提取任务 → 返回。
 * 缓存产物：{replayCacheDir}/{sliceId}/replay/**（障碍 10s 分段 / 消息 JSONL /
 * 感知 10s 分段 / 地图 10s 分段）。</p>
 *
 * <p>异常约定（RuntimeException message 前缀由 Controller 映射为错误码）：</p>
 * <ul>
 *   <li>SLICE_NOT_FOUND — 切片不存在（404）</li>
 *   <li>REPLAY_TOPIC_NOT_FOUND — topic 不在切片 topic 清单（404）</li>
 *   <li>REPLAY_EXTRACT_IN_PROGRESS — 同切片其他参数提取任务进行中（409，按 slice 串行）</li>
 *   <li>INVALID_PARAMETER — 时间/参数非法（400）</li>
 * </ul>
 *
 * <p>v1.4 修正：/momenta_public/*、/navigation/* 实测 frame_id 内嵌 JSON 业务负载，
 * 不再排除（无 REPLAY_TOPIC_NO_PAYLOAD）。</p>
 *
 * @feature: replay
 */
public interface ReplayService {

    /**
     * 获取回放页初始化上下文
     * <p>
     * GET /v1/data-slices/{sliceId}/replay/context
     */
    ReplayContextResponse getContext(String sliceId);

    /**
     * 按时间窗口获取障碍物帧（read-through 缓存）
     * <p>
     * GET /v1/data-slices/{sliceId}/replay/obstacles
     *
     * @return 命中 → frame 非空；未命中 → taskId 非空（任务已创建/复用）
     */
    ObstacleQueryResult getObstacles(String sliceId, String time, Integer windowMs, String sensorTypes);

    /**
     * 按时间窗口获取融合感知帧（read-through 缓存）
     * <p>
     * GET /v1/data-slices/{sliceId}/replay/perception
     *
     * <p>坐标系：frame=global（WGS84 投影局部网格，非 ego 系，unit: m），
     * 与雷达/超声波（ego 系）、ddld 地图（ego 系）不得直接叠加。</p>
     *
     * @return 命中 → frame 非空；未命中 → taskId 非空（任务已创建/复用）
     */
    ReplayFrameQueryResult<PerceptionFrameResponse> getPerception(String sliceId, String time, Integer windowMs);

    /**
     * 按时间窗口获取 ddld 地图帧（read-through 缓存）
     * <p>
     * GET /v1/data-slices/{sliceId}/replay/map
     *
     * <p>坐标系：frame=ego（ddld 局部系，x 前 y 左 z 上，unit: m），
     * 与 smooth_object（全局系 WGS84 投影）坐标系不同，不得直接叠加。</p>
     *
     * @return 命中 → frame 非空；未命中 → taskId 非空（任务已创建/复用）
     */
    ReplayFrameQueryResult<MapFrameResponse> getMap(String sliceId, String time, Integer windowMs);

    /**
     * 查询 Raw 消息流（分页）
     * <p>
     * GET /v1/data-slices/{sliceId}/replay/messages
     */
    RawMessagePageResponse getMessages(String sliceId, String topic, String startTs, String endTs,
                                       Integer offset, Integer limit);

    /**
     * 切片内的感知 topic（/momenta_public/perception/*，frame_id 内嵌融合感知
     * JSON，如 smooth_object），按 topic 升序
     */
    List<String> perceptionTopics(String sliceId);

    /**
     * 切片内的地图 topic（/momenta_public/ddld/*，frame_id 内嵌 ddld 地图 JSON，
     * 如 landmark_vis），按 topic 升序
     */
    List<String> mapTopics(String sliceId);

    /**
     * 获取 ROSData 回放数据（导入流水线 extract_replay.py 产物，api.md ROSData
     * 顶层 JSON：allTimestamps / objectMessages / planningTrajectoryMessages /
     * lineMessages / referenceLineMessages / roadBoundariesMessages /
     * trafficLightMessages / roadSignMessages）
     * <p>
     * GET /v1/data-slices/{sliceId}/replay/rosdata
     *
     * @throws RuntimeException REPLAY_DATA_NOT_FOUND（404，产物未生成/已归档）
     */
    JsonNode getRosData(String sliceId);
}
