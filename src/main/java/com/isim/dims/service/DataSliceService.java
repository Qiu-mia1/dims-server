package com.isim.dims.service;

import com.isim.dims.dto.SliceDetailResponse;
import com.isim.dims.dto.SliceListResponse;
import com.isim.dims.dto.SliceSearchRequest;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * 数据切片 Service 接口
 *
 * 负责数据切片的上传导入、多条件检索、详情查询和删除操作。
 * 上传操作会触发异步解析流水线（Python 算法引擎）。
 * 删除操作会清理文件+级联删除记录并广播 Kafka 事件。
 * @feature: slice
 */
public interface DataSliceService {

    /**
     * 上传导入：接收文件 + 名称 + 数据来源 → 创建记录 + 异步启动解析流水线 → 返回 sliceId + taskId
     *
     * @param file 上传的 ROS1 bag 文件（.bag）或 CFDI 数据包（.tar.gz）
     * @param name 切片名称（可选，默认取文件名）
     * @param source 数据来源提供方（必填）：MMT | ZYT | Hirain
     * @return 包含 slice_id、task_id、status 的 Map
     */
    Map<String, String> upload(MultipartFile file, String name, String source);

    /**
     * 多条件检索（分页）
     *
     * @param request 检索请求参数（时间范围、传感器类型、GPS、标签、关键字、分页、排序）
     * @return 分页的切片列表响应
     */
    SliceListResponse search(SliceSearchRequest request);

    /**
     * 切片详情（含 streams 列表）
     *
     * @param sliceId 切片 UUID
     * @return 切片详情响应（含 SensorStream 列表）
     * @throws RuntimeException 如果切片不存在
     */
    SliceDetailResponse getDetail(String sliceId);

    /**
     * 删除切片（清理文件 + 级联删除记录 + Kafka 事件）
     *
     * @param sliceId 切片 UUID
     * @throws RuntimeException 如果切片不存在
     */
    void delete(String sliceId);

    /**
     * 准备下载 OpenSCENARIO 产物（xosc + xodr）。
     * 文件在写入响应时才打开，避免把大文件读入 JVM 内存。
     */
    OpenScenarioDownload prepareOpenScenarioDownload(String sliceId);

    /** 将 OpenSCENARIO 的两个文件流式写入 ZIP 响应。 */
    void writeOpenScenarioZip(OpenScenarioDownload download, OutputStream output) throws IOException;

    /** 准备下载 CARLA 场景包（xosc + xodr + trajectory JSON + manifest）。 */
    CarlaDownload prepareCarlaDownload(String sliceId);

    /** 将 CARLA 场景包流式写入 ZIP 响应。 */
    void writeCarlaZip(CarlaDownload download, OutputStream output) throws IOException;

    /** 下载原始 ROS1 bag 文件。 */
    DownloadContent downloadRosbag(String sliceId);

    /**
     * 仅校验切片 bag 可下载（切片存在 + 未归档 + 对象存在），**不打开数据流**。
     *
     * <p>给「只需要知道能不能下」的调用方用（票据接口 {@code /downloads/rosbag/ticket}）。
     * 不要用 {@link #downloadRosbag(String)} 来代替本方法做校验：它返回的流一旦被关闭，
     * HttpClient 会把未读内容读干（连接复用所需），几百 MB 的 bag 会让调用方阻塞分钟级。
     *
     * @throws RuntimeException SLICE_NOT_FOUND / BAG_FILE_NOT_FOUND / StorageArchivedException
     */
    void assertRosbagDownloadable(String sliceId);

    /**
     * 解析切片原始产物的下载文件名（切片名 + 按存储 key 推导的扩展名：.bag / .zip / .tar.gz），
     * **不打开数据流**。
     *
     * <p>产物格式不止 bag：恒润（Hirain）切片为转换产物打包的 zip，CFDI 为 tar.gz，
     * 因此文件名后缀不能再写死 {@code .bag}。
     *
     * @throws RuntimeException SLICE_NOT_FOUND / BAG_FILE_NOT_FOUND / StorageArchivedException
     */
    String resolveRosbagFileName(String sliceId);

    /**
     * 准备下载切片「日志记录」，**不打开数据流**。
     *
     * <p>日志与 bag 不同源：bag 在对象存储里，日志是导入流程落盘的会话目录，路径记录在
     * {@code data_slice.import_source_dir}。该路径是<b>服务器/容器视角</b>的 POSIX 路径
     * （{@code /data/downloads/...}），进程未必能直接看见 —— 因此读取通道有两种：本地文件系统
     * （进程已挂到同一路径）与 NFS 直连（按 NFSv3 协议读服务端导出目录），由
     * {@code dims.download.log.source} 选择（{@code auto} 先试本地再退 NFS）。
     *
     * <p>「日志」口径（{@code dir-names}/{@code dir-name-contains}/{@code meta-names} 可配）：
     * <ol>
     *   <li>任意层级下目录名为 {@code log}/{@code logs} 的目录，<b>整目录</b>收；</li>
     *   <li>该目录的<b>兄弟元数据文件</b>（默认 {@code meta}、{@code log_info.json}）一并收 ——
     *       日志要能解读离不开它，而它与 {@code log/} 同级；</li>
     *   <li>目录名含 {@code forever_log} 的整目录收（另一种会话布局，日志包直接以该名命名）；</li>
     *   <li>文件名形如 {@code *.log*}/{@code *.log_*} 的单独收（兜底非日志目录下的日志）。</li>
     * </ol>
     * 平台簿记文件（{@code upload.done}/{@code dump_finish}/{@code good_flag}/{@code filter_meta}）
     * 与 {@code .mfbag}（图像/点云包，几十 MB）一律不收。命中 1 个直出，命中多个打包 ZIP。
     *
     * <p>库表路径与实际存放不一致时（现网带/不带 {@code {yyyyMMdd}} 日期段两种都有），
     * 会按候选形态依次尝试，命中非原始形态时打 WARN 而非直接报错。
     *
     * <p>校验分三道，对应三类「下不了」的真实原因，调用方据此给用户可读提示：
     * ① 目录未记录或不在允许的根目录内；② 候选路径都不存在/不可读；③ 目录下没有日志文件或日志过大。
     *
     * @throws RuntimeException SLICE_NOT_FOUND / LOG_PATH_MISSING / LOG_PATH_NOT_ALLOWED
     *                          / LOG_PATH_NOT_FOUND / LOG_NOT_FOUND / LOG_TOO_LARGE
     *                          / LOG_STORE_UNAVAILABLE（NFS 连不上/未配置）
     */
    LogDownload prepareLogDownload(String sliceId);

    /**
     * 将日志内容流式写入响应：单文件直出，多文件打为 ZIP。
     *
     * <p>只做逐块搬运（{@code transferTo}），不把日志读进 JVM 内存；读取通道按
     * {@link LogSource#type()} 重建，所以本地与 NFS 两种来源共用这一段输出逻辑。
     */
    void writeLogBundle(LogDownload download, OutputStream output) throws IOException;

    record OpenScenarioDownload(String fileName, String xoscKey, String xodrKey) {
    }

    record CarlaDownload(String fileName, List<String> keys) {
    }

    record DownloadContent(InputStream stream, long size, String fileName) {
    }

    /**
     * 日志的来源（库表路径 → 实际在哪读）。
     *
     * @param type  来源类型（{@code local} / {@code nfs}）；{@code writeLogBundle} 据此重建读取通道
     * @param label 人类可读来源标签（{@code local-filesystem} / {@code nfs://10.130.212.160:/nfs/dims}），
     *              写进取票响应与错误消息，便于区分「读的是哪一边」
     * @param dir   实际被读取的目录，位于该来源自己的路径命名空间内
     *              （本地是操作系统路径，NFS 是导出根之下的路径）
     */
    record LogSource(String type, String label, String dir) {
    }

    /**
     * 日志下载内容（见 {@link #prepareLogDownload(String)}）。
     *
     * @param fileName  建议的下载文件名：单文件取原文件名，多文件取「切片名_logs.zip」
     * @param zip       是否以 ZIP 输出；为 {@code true} 时 {@code totalSize} 只是日志原始字节数，
     *                  **不能**当作响应的 Content-Length（压缩后长度无法预知）
     * @param files     待输出的日志文件，已按相对路径排序以保证结果可复现
     * @param totalSize 日志原始字节总数；单文件直出时即等于响应长度
     * @param source    实际读取来源（可能因候选形态/路径映射而与库表记录不同；
     *                  取票接口回传给前端，便于排查「下的到底是不是这个目录」）
     */
    record LogDownload(String fileName, boolean zip, List<LogFile> files, long totalSize,
                       LogSource source) {

        /** 实际被读取的目录（{@link LogSource#dir()}），供取票响应回显「下的到底是哪个目录」。 */
        public String sourceDir() {
            return source.dir();
        }

        /**
         * 单个日志文件。
         *
         * @param path      该来源命名空间下的完整路径，供 {@code writeLogBundle} 打开
         * @param entryName 相对读取根目录的路径，ZIP 条目名统一用 {@code /} 分隔
         */
        public record LogFile(String path, String entryName, long size) {
        }
    }
}
