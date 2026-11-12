package com.isim.dims.service;

import java.util.List;

/**
 * 本地目录导入异步流水线 Service
 *
 * 独立 Bean 确保 @Async 通过 Spring AOP 代理正常生效。
 * 逐会话串行执行（稳定优先，避免并发争抢 Docker/解析资源）：
 *   UPLOADING → CONVERTING (10→50) → [复用 IngestionPipelineService: PARSING→TAGGING→READY]
 *
 * 关键步骤失败 → 单会话 ERROR + 继续下一个会话；非关键步骤（h265复制、标定解析、元数据解析）→ WARN + 继续。
 * @feature: ingestion
 */
public interface LocalDirectoryImportPipelineService {

    /**
     * 异步处理一批已导入会话（每个会话独立异常隔离，串行执行）
     *
     * @param sessions 同步阶段已创建 DataSlice + IngestionTask 的会话列表
     */
    void processImport(List<ImportedSession> sessions);

    void processOneImport(ImportedSession session);

    /**
     * 同步阶段产出的待处理会话载体
     */
    class ImportedSession {

        /** 切片 UUID */
        private final String sliceId;

        /** 导入任务 UUID */
        private final String taskId;

        /** 源会话目录绝对路径（只读，其 public/ 将被复制到工作区） */
        private final String sessionDirPath;

        /** 源 MD5 子目录名 */
        private final String md5;

        /** 会话目录名 */
        private final String sessionId;

        /** VIN 快照（可为 null） */
        private final String vin;

        /** 车辆编号（可为 null） */
        private final String vehicleNo;

        public ImportedSession(String sliceId, String taskId, String sessionDirPath,
                               String md5, String sessionId, String vin, String vehicleNo) {
            this.sliceId = sliceId;
            this.taskId = taskId;
            this.sessionDirPath = sessionDirPath;
            this.md5 = md5;
            this.sessionId = sessionId;
            this.vin = vin;
            this.vehicleNo = vehicleNo;
        }

        public String getSliceId() {
            return sliceId;
        }

        public String getTaskId() {
            return taskId;
        }

        public String getSessionDirPath() {
            return sessionDirPath;
        }

        public String getMd5() {
            return md5;
        }

        public String getSessionId() {
            return sessionId;
        }

        public String getVin() {
            return vin;
        }

        public String getVehicleNo() {
            return vehicleNo;
        }
    }
}
