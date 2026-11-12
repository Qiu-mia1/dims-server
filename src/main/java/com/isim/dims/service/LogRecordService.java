package com.isim.dims.service;

import com.baidubce.services.bos.model.BosObjectSummary;
import com.isim.dims.dto.LogRecordListResponse;
import com.isim.dims.entity.LogRecord;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;

/**
 * 日志包记录服务
 *
 * <p>负责把 BOS 子集目录下以 {@code _log} 结尾的独立日志文件（目前线上类型：
 * forever_log / bsw_log）的元数据登记到 {@code log_record}，并提供列表查询与
 * 「整个目录打包下载」能力。</p>
 *
 * @feature: ingestion
 */
public interface LogRecordService {

    /**
     * 登记一个子集目录下所有 {@code _log} 文件的元数据（按 dir_prefix 幂等 upsert）。
     *
     * <p>同一文件所在目录只生成一条记录：该目录下的多个 {@code _log} 文件合并为一条，
     * 下载时按 dir_prefix 打包整个目录。</p>
     *
     * <p>元数据来源：优先取同一会话目录（含上级目录）下的 {@code meta} 文件，
     * 其次取对象 key 所在目录信息。</p>
     *
     * @param bucket  对象存储桶
     * @param subDir  子集目录前缀（{@code {bosPrefix}/{yyyyMMdd}/{VIN}/{session}_public}）
     * @param objects 该子集目录下的所有 BOS 对象
     * @return 新增/更新的日志目录条数
     */
    int recordLogObjects(String bucket, String subDir, Collection<BosObjectSummary> objects);

    /**
     * 从 BOS 重新扫描并补登记日志记录（用于历史数据回填）。
     *
     * <p>调度按子集目录增量处理并持久化已处理记录，历史子集目录不会再次进入
     * meta-only 流程，因此需要该接口补建索引。</p>
     *
     * @param date 可选，{@code yyyyMMdd} 日期目录；为空时扫描整个前缀
     * @return 新增/更新的日志记录条数
     */
    int rebuild(String date);

    /**
     * 分页查询日志记录，按日志时间倒序。
     */
    LogRecordListResponse list(String vin, String logType, String eventName, String collectType,
                               String project, String carType, String keyword,
                               int page, int pageSize);

    /**
     * 按主键查询日志记录，不存在返回 null。
     */
    LogRecord getById(String id);

    /**
     * 把日志文件所在整个目录打包成 zip 写入输出流。
     *
     * <p>优先读取本地已下载文件，缺失时从 BOS 流式读取；不落临时文件。</p>
     */
    void writeBundle(String id, OutputStream out) throws IOException;

    /**
     * 打包下载的文件名，如 {@code forever_log_3b4ae729_20260823.zip}。
     */
    String bundleFileName(LogRecord record);
}
