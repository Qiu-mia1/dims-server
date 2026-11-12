package com.isim.dims.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 以图搜图 / 以文搜图 / 特征搜图（SigLIP2 embedding）配置
 *
 * <p>读取 application.yml 的 dims.siglip.* 配置项。<br>
 * 未部署/未启用 embedding 服务时（enabled=false），检索接口返回
 * FEATURES_DISABLED 错误，其余功能（每 5 秒帧图浏览）不受影响。</p>
 * @feature: visualization
 */
@Component
@ConfigurationProperties(prefix = "dims.siglip")
@Data
public class SigLIPConfig {

    /** 语义检索开关（默认关闭，避免未部署服务拖垮启动） */
    private boolean enabled = false;

    /** SigLIP2 FastAPI 服务地址，如 http://127.0.0.1:8000 */
    private String url = "http://127.0.0.1:8000";

    /** embedding 特征向量维度（SigLIP2 base 为 768） */
    private int embedDims = 768;

    /** 单次 embedding 请求超时（秒） */
    private int timeoutSec = 30;

    /** 视频语义检索候选池大小（帧级 + 片段级 HNSW 召回后进入 Reranker 的规模） */
    private int searchCandidateLimit = 300;

    /** 视频语义检索最终返回条数（Reranker 之后的 Top N） */
    private int searchResultLimit = 60;

    /** 相邻命中帧合并为同一时间区间的最大间隔（秒） */
    private int searchSegmentGapSec = 6;

    /** 单个时间区间最大跨度（秒），超出则切分 */
    private int searchSegmentMaxSpanSec = 35;

    /** 每个时间区间返回的关键帧数量 */
    private int searchTopFrames = 2;

    /**
     * Reranker 之后是否按 slice_id 去重（每个切片只保留得分最高的区间），默认开启。
     *
     * <p>一个切片通常有 2~3 路摄像头，每路产出一个命中区间，不去重时 Top N
     * 会被少数强匹配切片重复占用（每个占 2~3 个名额），实际召回的切片数
     * 只有 N/2~N/3。开启后 Top N 即 N 个互不相同的切片，且排序用该切片的
     * 最佳区间得分，语义上也更正确。</p>
     */
    private boolean searchDedupBySlice = true;
}
