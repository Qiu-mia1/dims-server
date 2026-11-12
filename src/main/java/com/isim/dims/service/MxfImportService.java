package com.isim.dims.service;

import com.isim.dims.dto.MxfImportResponse;
import org.springframework.web.multipart.MultipartFile;

/**
 * MXF 埋点数据包导入服务。
 *
 * <p>接口签名参考 {@code LocalDirectoryImportService.importDirectory}，
 * 但目标从"目录中的 CFDI/数据切片"变为"上传的 MXF 埋点 .tar.gz 压缩包"。
 *
 * @feature: mxf
 */
public interface MxfImportService {

    /**
     * 导入一个或多个 MXF 埋点数据包。
     *
     * @param files   上传的压缩包文件列表（每个 .tar.gz）
     * @param source  调用方 source（MMT/ZYT/Hirain/手工导入 等）
     * @return 汇总结果，包含每个文件的解析状态与入库统计
     */
    MxfImportResponse importPackets(MultipartFile[] files, String source);

    /**
     * 清理存量原始日志对象（{@code mxf/packets/<id>/data.log}）。
     *
     * <p>导入流程已不再保存原始日志，本方法用于回收历史遗留对象：
     * 删除对象存储文件并清空 {@code mxf_packet.data_object_key}，
     * 保留包元数据与聚合快照，不影响看板与轨迹查询。</p>
     *
     * @param limit 本次最多处理的包数（0 或负 = 不限）
     * @return 成功清理的对象个数
     */
    int purgeRawLogs(int limit);
}
