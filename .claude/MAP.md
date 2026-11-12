# DIMS Server 代码地图

> 自动生成于 2026-08-16 17:16 ｜ dims-server ｜ Java 文件: 242 · Controller: 14

> 生成命令：`python .claude/scripts/generate-map.py` ｜ 业务域标注约定：文件头部 `@feature:` / `@desc:` / `@see:`

## 分层总览

┌──────────────┬────────┬───────────────────────────────────────────┐
│ 层           │ 文件数 │ 说明                                      │
├──────────────┼────────┼───────────────────────────────────────────┤
│ 入口         │ 1      │ SpringBoot 启动类                         │
│ Controller   │ 14     │ REST 控制器（@RestController + 路径映射） │
│ Service 接口 │ 26     │ 业务接口（interface）                     │
│ Service 实现 │ 27     │ 业务实现（@Service class）                │
│ Mapper       │ 19     │ MyBatis Mapper（@Mapper / BaseMapper）    │
│ Entity       │ 19     │ 数据库实体（@TableName）                  │
│ DTO          │ 56     │ 请求/响应数据传输对象                     │
│ Config       │ 11     │ Spring 配置（@Configuration）             │
│ Util         │ 7      │ 工具类                                    │
│ Event        │ 3      │ Kafka 事件（Payload / 发布器）            │
│ Exception    │ 6      │ 异常定义                                  │
│ Test         │ 34     │ 单元/集成测试                             │
└──────────────┴────────┴───────────────────────────────────────────┘

## 入口 层

> SpringBoot 启动类（1 个文件）

┌──────────────────┬───────────────────┬─────────────────────────────┬───────────┐
│ 文件             │ 关键信息          │ 描述                        │ @see 依赖 │
├──────────────────┼───────────────────┼─────────────────────────────┼───────────┤
│ Application.java │ class Application │ DIMS — 数据接入与管理子系统 │ -         │
└──────────────────┴───────────────────┴─────────────────────────────┴───────────┘

## Controller 层

> REST 控制器（@RestController + 路径映射）（14 个文件）

┌────────────────────────────────────────┬──────────────────────────────────────────────────────────┬─────────────────────────────────┬───────────┐
│ 文件                                   │ 关键信息                                                 │ 描述                            │ @see 依赖 │
├────────────────────────────────────────┼──────────────────────────────────────────────────────────┼─────────────────────────────────┼───────────┤
│ controller/CalibrationController.java  │ @RestController · @Slf4j ｜ GET /v1/data-slices/{sliceId}/calibrations POST /v1/data-slices/{sliceId}/calibrations │ 传感器标定 Controller           │ -         │
│ controller/CockpitStatsController.java │ @RestController · @Slf4j ｜ GET /v1/cockpit-stats/slices/{sliceId} POST /v1/cockpit-stats/slices/{sliceId}/trigger │ 驾驶舱统计指标 REST API.        │ -         │
│ controller/DashboardController.java    │ @RestController · @Slf4j ｜ GET /v1/dashboard/stats GET /v1/dashboard/recent-uploads GET /v1/dashboard/distribution GET /v1/dashboard/alerts GET /v1/dashboard/trends GET /v1/dashboard/vehicle-types │ 仪表盘 Controller               │ -         │
│ controller/DataSliceController.java    │ @RestController · @Slf4j ｜ POST /v1/data-slices POST /v1/data-slices/directory-import GET /v1/data-slices/{sliceId} DELETE /v1/data-slices/{sliceId} GET /v1/data-slices │ 数据切片 Controller             │ -         │
│ controller/ExportController.java       │ @RestController · @Slf4j ｜ POST /v1/data-slices/{sliceId}/exports GET /v1/exports/{exportId} GET /v1/data-slices/{sliceId}/exports GET /v1/exports GET /v1/exports/{exportId}/download │ 数据导出 Controller             │ -         │
│ controller/ReplayController.java       │ @RestController · @Slf4j ｜ GET /v1/data-slices/{sliceId}/replay/context GET /v1/data-slices/{sliceId}/replay/obstacles GET /v1/data-slices/{sliceId}/replay/perception GET /v1/data-slices/{sliceId}/replay/map GET /v1/data-slices/{sliceId}/replay/messages POST /v1/data-slices/{sliceId}/replay/extract │ 2D 数据回放 Controller          │ -         │
│ controller/ReplayExtractionController.java │ @RestController · @Slf4j ｜ GET /v1/replay-extractions/{taskId} │ 回放提取任务查询 Controller     │ -         │
│ controller/SceneExtractionController.java │ @RestController · @Slf4j ｜ POST /v1/data-slices/{sliceId}/scene-extraction GET /v1/scene-extractions/{taskId} GET /v1/data-slices/{sliceId}/scenes GET /v1/data-slices/{sliceId}/scene-extraction/files/content GET /v1/data-slices/{sliceId}/scene-extraction/latest GET /v1/scenes/{sceneId} │ 场景提取 Controller             │ -         │
│ controller/SceneRuleController.java    │ @RestController · @Slf4j ｜ GET /v1/scene-rules GET /v1/scene-rules/{id} POST /v1/scene-rules PUT /v1/scene-rules/{id} PATCH /v1/scene-rules/{id}/toggle PUT /v1/scene-rules/priority GET /v1/scene-rules/export GET /v1/scene-rules/config │ 场景规则 Controller             │ -         │
│ controller/TagController.java          │ @RestController · @Slf4j ｜ GET /v1/tags POST /v1/tags PUT /v1/tags/{tagId} DELETE /v1/tags/{tagId} GET /v1/data-slices/{sliceId}/tags PUT /v1/data-slices/{sliceId}/tags │ 标签 Controller                 │ -         │
│ controller/TaskController.java         │ @RestController · @Slf4j ｜ GET /v1/tasks/{taskId} GET /v1/health │ 任务与系统 Controller           │ -         │
│ controller/TrajectoryController.java   │ @RestController · @Slf4j ｜ GET /v1/data-slices/{sliceId}/trajectory POST /v1/data-slices/{sliceId}/trajectory/extract GET /v1/trajectory-tasks/{taskId} │ GPS 轨迹 Controller             │ -         │
│ controller/VideoStreamingController.java │ @RestController · @Slf4j ｜ GET /data/videos/{sliceId}/{filename:.+} │ 视频文件异步流式服务 Controller │ -         │
│ controller/VisualizationController.java │ @RestController · @Slf4j ｜ GET /v1/data-slices/{sliceId}/video-streams POST /v1/data-slices/{sliceId}/video-streams/extract POST /v1/data-slices/{sliceId}/signal-data GET /v1/signal-extractions/{taskId} GET /v1/video-extractions/{taskId} │ 数据可视化 Controller           │ -         │
└────────────────────────────────────────┴──────────────────────────────────────────────────────────┴─────────────────────────────────┴───────────┘

## Service 接口 层

> 业务接口（interface）（26 个文件）

┌────────────────────────────────────────┬─────────────────────────────────────────────────┬──────────────────────────────────────────┬───────────┐
│ 文件                                   │ 关键信息                                        │ 描述                                     │ @see 依赖 │
├────────────────────────────────────────┼─────────────────────────────────────────────────┼──────────────────────────────────────────┼───────────┤
│ service/BosStorageService.java         │ class BosStorageService                         │ 百度 BOS 对象存储下载服务                │ -         │
│ service/CalibrationService.java        │ interface CalibrationService                    │ 传感器标定 Service                       │ -         │
│ service/CfdiConversionService.java     │ interface CfdiConversionService                 │ CFDI 数据包转换 Service                  │ -         │
│ service/CfdiMetadataService.java       │ interface CfdiMetadataService                   │ CFDI 元数据解析 Service                  │ -         │
│ service/CfdiPipelineService.java       │ interface CfdiPipelineService                   │ CFDI 数据包导入流水线 Service            │ -         │
│ service/DashboardService.java          │ interface DashboardService                      │ 仪表盘 Service 接口                      │ -         │
│ service/DashboardStatsPipelineService.java │ interface DashboardStatsPipelineService         │ 驾驶舱统计提取流水线 Service 接口.       │ -         │
│ service/DataSliceService.java          │ interface DataSliceService                      │ 数据切片 Service 接口                    │ -         │
│ service/ExportPipelineService.java     │ interface ExportPipelineService                 │ 数据导出流水线 Service                   │ -         │
│ service/ExportService.java             │ interface ExportService                         │ 数据导出 Service 接口                    │ -         │
│ service/GpsTrajectoryService.java      │ interface GpsTrajectoryService extends IService │ GPS 轨迹点 Service 接口                  │ -         │
│ service/IngestionPipelineService.java  │ interface IngestionPipelineService              │ 数据导入流水线 Service                   │ -         │
│ service/IngestionService.java          │ interface IngestionService                      │ 导入任务 Service 接口                    │ -         │
│ service/LocalDirectoryImportPipelineService.java │ interface LocalDirectoryImportPipelineService   │ 本地目录导入异步流水线 Service           │ -         │
│ service/LocalDirectoryImportService.java │ interface LocalDirectoryImportService           │ 本地目录导入 Service                     │ -         │
│ service/ReplayExtractionPipelineService.java │ interface ReplayExtractionPipelineService       │ 回放提取异步流水线 Service               │ -         │
│ service/ReplayExtractionTaskService.java │ interface ReplayExtractionTaskService           │ 回放提取任务管理 Service                 │ -         │
│ service/ReplayService.java             │ interface ReplayService                         │ 2D 回放 Service（read-through 缓存编排） │ -         │
│ service/SceneExtractionPipelineService.java │ interface SceneExtractionPipelineService        │ 场景提取流水线 Service                   │ -         │
│ service/SceneRuleService.java          │ interface SceneRuleService                      │ 场景规则 Service 接口                    │ -         │
│ service/StorageService.java            │ interface StorageService                        │ 存储服务抽象（本地文件系统 → MinIO 迁移） │ -         │
│ service/StorageStat.java               │ class StorageStat                               │ -                                        │ -         │
│ service/TagService.java                │ interface TagService                            │ 标签 Service 接口                        │ -         │
│ service/TrajectoryService.java         │ interface TrajectoryService                     │ GPS 轨迹 Service 接口                    │ -         │
│ service/VisualizationPipelineService.java │ interface VisualizationPipelineService          │ 可视化数据异步提取流水线 Service         │ -         │
│ service/VisualizationService.java      │ interface VisualizationService                  │ 数据可视化 Service 接口                  │ -         │
└────────────────────────────────────────┴─────────────────────────────────────────────────┴──────────────────────────────────────────┴───────────┘

## Service 实现 层

> 业务实现（@Service class）（27 个文件）

┌────────────────────────────────────────┬──────────────────────────────────────────────────────────┬──────────────────────────────────────────┬───────────┐
│ 文件                                   │ 关键信息                                                 │ 描述                                     │ @see 依赖 │
├────────────────────────────────────────┼──────────────────────────────────────────────────────────┼──────────────────────────────────────────┼───────────┤
│ service/impl/CalibrationServiceImpl.java │ class CalibrationServiceImpl implements CalibrationService │ 传感器标定 Service 实现                  │ -         │
│ service/impl/CfdiConversionServiceImpl.java │ class CfdiConversionServiceImpl implements CfdiConversionService │ CFDI 数据包转换 Service 实现             │ -         │
│ service/impl/CfdiMetadataServiceImpl.java │ class CfdiMetadataServiceImpl implements CfdiMetadataService │ CFDI 元数据解析 Service 实现             │ -         │
│ service/impl/CfdiPipelineServiceImpl.java │ class CfdiPipelineServiceImpl implements CfdiPipelineService │ CFDI 数据包导入流水线 Service 实现       │ -         │
│ service/impl/DashboardServiceImpl.java │ class DashboardServiceImpl implements DashboardService   │ 仪表盘 Service 实现                      │ -         │
│ service/impl/DashboardStatsPipelineServiceImpl.java │ class DashboardStatsPipelineServiceImpl implements DashboardStatsPipelineService │ 驾驶舱统计提取流水线 Service 实现.       │ -         │
│ service/impl/DataSliceServiceImpl.java │ class DataSliceServiceImpl implements DataSliceService   │ 数据切片 Service 实现                    │ -         │
│ service/impl/DetectedScenePersistenceService.java │ class DetectedScenePersistenceService                    │ detected_scene 表持久化服务              │ -         │
│ service/impl/ExportPipelineServiceImpl.java │ class ExportPipelineServiceImpl implements ExportPipelineService │ 数据导出流水线 Service 实现              │ -         │
│ service/impl/ExportServiceImpl.java    │ class ExportServiceImpl implements ExportService         │ 数据导出 Service 实现                    │ -         │
│ service/impl/GpsTrajectoryServiceImpl.java │ class GpsTrajectoryServiceImpl implements GpsTrajectoryService │ GPS 轨迹点 Service 实现                  │ -         │
│ service/impl/IngestionPipelineServiceImpl.java │ class IngestionPipelineServiceImpl implements IngestionPipelineService │ 数据导入流水线 Service 实现              │ -         │
│ service/impl/IngestionServiceImpl.java │ class IngestionServiceImpl implements IngestionService   │ 导入任务 Service 实现                    │ -         │
│ service/impl/LocalDirectoryImportPipelineServiceImpl.java │ class LocalDirectoryImportPipelineServiceImpl implements LocalDirectoryImportPipelineService │ 本地目录导入异步流水线 Service 实现      │ -         │
│ service/impl/LocalDirectoryImportServiceImpl.java │ class LocalDirectoryImportServiceImpl implements LocalDirectoryImportService │ 本地目录导入 Service 实现                │ -         │
│ service/impl/LocalStorageService.java  │ class LocalStorageService implements StorageService      │ 本地文件系统存储实现（MinIO 迁移，默认模式） │ -         │
│ service/impl/MinioStorageService.java  │ class MinioStorageService implements StorageService      │ MinIO 对象存储实现（MinIO 迁移，dims.storage.type=minio） │ -         │
│ service/impl/ReplayExtractionPipelineServiceImpl.java │ class ReplayExtractionPipelineServiceImpl implements ReplayExtractionPipelineService │ 回放提取异步流水线 Service 实现          │ -         │
│ service/impl/ReplayExtractionTaskServiceImpl.java │ class ReplayExtractionTaskServiceImpl implements ReplayExtractionTaskService │ 回放提取任务管理 Service 实现            │ -         │
│ service/impl/ReplayServiceImpl.java    │ class ReplayServiceImpl implements ReplayService         │ 2D 回放 Service 实现（read-through 缓存编排） │ -         │
│ service/impl/SceneExtractionPipelineServiceImpl.java │ class SceneExtractionPipelineServiceImpl implements SceneExtractionPipelineService │ 场景提取流水线 Service 实现              │ -         │
│ service/impl/SceneRuleServiceImpl.java │ class SceneRuleServiceImpl implements SceneRuleService   │ 场景规则 Service 实现                    │ -         │
│ service/impl/StorageAccess.java        │ class StorageAccess                                      │ 存储访问门面（MinIO 迁移）——业务层唯一存储入口 │ -         │
│ service/impl/TagServiceImpl.java       │ class TagServiceImpl implements TagService               │ 标签 Service 实现                        │ -         │
│ service/impl/TrajectoryServiceImpl.java │ class TrajectoryServiceImpl implements TrajectoryService │ GPS 轨迹 Service 实现                    │ -         │
│ service/impl/VisualizationPipelineServiceImpl.java │ class VisualizationPipelineServiceImpl implements VisualizationPipelineService │ 可视化数据异步提取流水线 Service 实现    │ -         │
│ service/impl/VisualizationServiceImpl.java │ class VisualizationServiceImpl implements VisualizationService │ 数据可视化 Service 实现                  │ -         │
└────────────────────────────────────────┴──────────────────────────────────────────────────────────┴──────────────────────────────────────────┴───────────┘

## Mapper 层

> MyBatis Mapper（@Mapper / BaseMapper）（19 个文件）

┌────────────────────────────────────────┬──────────────────────────────────────────────────────────┬─────────────────────────────┬───────────┐
│ 文件                                   │ 关键信息                                                 │ 描述                        │ @see 依赖 │
├────────────────────────────────────────┼──────────────────────────────────────────────────────────┼─────────────────────────────┼───────────┤
│ mapper/CameraCalibrationMapper.java    │ interface CameraCalibrationMapper extends BaseMapper     │ 摄像头标定参数 Mapper       │ -         │
│ mapper/CfdiMetaMapper.java             │ interface CfdiMetaMapper extends BaseMapper              │ CFDI 元数据 Mapper          │ -         │
│ mapper/DashboardStatsTaskMapper.java   │ interface DashboardStatsTaskMapper extends BaseMapper    │ 驾驶舱统计提取任务 Mapper   │ -         │
│ mapper/DataSliceMapper.java            │ interface DataSliceMapper extends BaseMapper             │ 数据切片 Mapper             │ -         │
│ mapper/DataSliceTagMapper.java         │ interface DataSliceTagMapper extends BaseMapper          │ 切片-标签关联 Mapper        │ -         │
│ mapper/DetectedSceneMapper.java        │ interface DetectedSceneMapper extends BaseMapper         │ 已检测场景 Mapper           │ -         │
│ mapper/ExportTaskMapper.java           │ interface ExportTaskMapper extends BaseMapper            │ 导出任务 Mapper             │ -         │
│ mapper/GpsTrajectoryMapper.java        │ interface GpsTrajectoryMapper extends BaseMapper         │ GPS 轨迹点 Mapper           │ -         │
│ mapper/IngestionTaskMapper.java        │ interface IngestionTaskMapper extends BaseMapper         │ 导入任务 Mapper             │ -         │
│ mapper/IngestionTaskStatusHistoryMapper.java │ interface IngestionTaskStatusHistoryMapper extends BaseMapper │ 导入任务状态变更历史 Mapper │ -         │
│ mapper/ReplayExtractionTaskMapper.java │ interface ReplayExtractionTaskMapper extends BaseMapper  │ 回放提取任务 Mapper         │ -         │
│ mapper/SceneExtractionTaskMapper.java  │ interface SceneExtractionTaskMapper extends BaseMapper   │ 场景提取任务 Mapper         │ -         │
│ mapper/SceneRuleConfigMapper.java      │ interface SceneRuleConfigMapper extends BaseMapper       │ 场景规则阈值配置 Mapper     │ -         │
│ mapper/SceneRuleMapper.java            │ interface SceneRuleMapper extends BaseMapper             │ 场景规则 Mapper             │ -         │
│ mapper/SensorCalibrationMapper.java    │ interface SensorCalibrationMapper extends BaseMapper     │ 传感器标定 Mapper           │ -         │
│ mapper/SensorStreamMapper.java         │ interface SensorStreamMapper extends BaseMapper          │ 传感器流 Mapper             │ -         │
│ mapper/SliceStatsMapper.java           │ interface SliceStatsMapper extends BaseMapper            │ 切片统计指标 Mapper         │ -         │
│ mapper/TagMapper.java                  │ interface TagMapper extends BaseMapper                   │ 标签库 Mapper               │ -         │
│ mapper/VinMd5MappingMapper.java        │ interface VinMd5MappingMapper extends BaseMapper         │ VIN-MD5 对应关系 Mapper     │ -         │
└────────────────────────────────────────┴──────────────────────────────────────────────────────────┴─────────────────────────────┴───────────┘

## Entity 层

> 数据库实体（@TableName）（19 个文件）

┌────────────────────────────────────────┬─────────────────────────────────────────────┬──────────────────────────────────────────┬───────────┐
│ 文件                                   │ 关键信息                                    │ 描述                                     │ @see 依赖 │
├────────────────────────────────────────┼─────────────────────────────────────────────┼──────────────────────────────────────────┼───────────┤
│ entity/CameraCalibration.java          │ @TableName("camera_calibration")            │ 摄像头标定参数表 (camera_calibration)    │ -         │
│ entity/CfdiMeta.java                   │ @TableName("cfdi_meta")                     │ CFDI 数据包元数据表 (cfdi_meta)          │ -         │
│ entity/DashboardStatsTask.java         │ @TableName("dashboard_stats_task")          │ 驾驶舱统计提取任务表 (dashboard_stats_task) │ -         │
│ entity/DataSlice.java                  │ @TableName("data_slice")                    │ 数据切片主表 (data_slice)                │ -         │
│ entity/DataSliceTag.java               │ @TableName("data_slice_tag")                │ 切片-标签关联表 (data_slice_tag)         │ -         │
│ entity/DetectedScene.java              │ @TableName("detected_scene")                │ 已检测场景表 (detected_scene)            │ -         │
│ entity/ExportTask.java                 │ @TableName("export_task")                   │ 导出任务表 (export_task)                 │ -         │
│ entity/GpsTrajectory.java              │ @TableName("gps_trajectory")                │ GPS 轨迹点表 (gps_trajectory)            │ -         │
│ entity/IngestionTask.java              │ @TableName("ingestion_task")                │ 导入任务表 (ingestion_task)              │ -         │
│ entity/IngestionTaskStatusHistory.java │ @TableName("ingestion_task_status_history") │ 导入任务状态变更历史表 (ingestion_task_status_history) │ -         │
│ entity/ReplayExtractionTask.java       │ @TableName("replay_extraction_task")        │ 回放提取任务表 (replay_extraction_task)  │ -         │
│ entity/SceneExtractionTask.java        │ @TableName("scene_extraction_task")         │ 场景提取任务表 (scene_extraction_task)   │ -         │
│ entity/SceneRule.java                  │ @TableName("scene_rule")                    │ 场景判断规则表 (scene_rule)              │ -         │
│ entity/SceneRuleConfig.java            │ @TableName("scene_rule_config")             │ 场景规则全局阈值配置表 (scene_rule_config) │ -         │
│ entity/SensorCalibration.java          │ @TableName("sensor_calibration")            │ 传感器安装标定表 (sensor_calibration)    │ -         │
│ entity/SensorStream.java               │ @TableName("sensor_stream")                 │ 传感器流索引表 (sensor_stream)           │ -         │
│ entity/SliceStats.java                 │ @TableName("slice_stats")                   │ 切片统计指标表 (slice_stats)             │ -         │
│ entity/Tag.java                        │ @TableName("tag")                           │ 标签库表 (tag)                           │ -         │
│ entity/VinMd5Mapping.java              │ @TableName("vin_md5_mapping")               │ VIN 与 MD5 对应关系表 (vin_md5_mapping)  │ -         │
└────────────────────────────────────────┴─────────────────────────────────────────────┴──────────────────────────────────────────┴───────────┘

## DTO 层

> 请求/响应数据传输对象（56 个文件）

┌────────────────────────────────────────┬────────────────────────────────────────────────┬──────────────────────────────────────────┬───────────┐
│ 文件                                   │ 关键信息                                       │ 描述                                     │ @see 依赖 │
├────────────────────────────────────────┼────────────────────────────────────────────────┼──────────────────────────────────────────┼───────────┤
│ dto/DashboardAlertsResponse.java       │ class DashboardAlertsResponse                  │ 仪表盘质量告警响应                       │ -         │
│ dto/DashboardDistributionResponse.java │ class DashboardDistributionResponse            │ 仪表盘分布统计响应                       │ -         │
│ dto/DashboardKpiCard.java              │ class DashboardKpiCard                         │ 仪表盘 KPI 卡片（核心指标卡 & 功能详情 KPI 卡共用） │ -         │
│ dto/DashboardKpiResponse.java          │ class DashboardKpiResponse                     │ 仪表盘核心指标卡响应                     │ -         │
│ dto/DashboardStatsResponse.java        │ class DashboardStatsResponse                   │ 仪表盘统计概览响应                       │ -         │
│ dto/DashboardTrendsResponse.java       │ class DashboardTrendsResponse                  │ 仪表盘时间趋势响应                       │ -         │
│ dto/DetectedSceneDTO.java              │ class DetectedSceneDTO                         │ 已检测场景（场景提取结果持久化后的 API 视图） │ -         │
│ dto/DirectoryImportRequest.java        │ class DirectoryImportRequest                   │ 本地目录导入请求参数                     │ -         │
│ dto/DirectoryImportResponse.java       │ class DirectoryImportResponse                  │ 本地目录导入响应（202）                  │ -         │
│ dto/ErrorResponse.java                 │ class ErrorResponse                            │ 统一错误结构                             │ -         │
│ dto/ExportListResponse.java            │ class ExportListResponse                       │ 全局导出任务列表响应（分页）             │ -         │
│ dto/ExportRequest.java                 │ class ExportRequest                            │ 导出请求参数                             │ -         │
│ dto/ExportResponse.java                │ class ExportResponse                           │ 导出任务详情响应                         │ -         │
│ dto/ExportTaskSummary.java             │ class ExportTaskSummary extends ExportResponse │ 导出任务摘要项（列表场景）               │ -         │
│ dto/ExtractRequest.java                │ class ExtractRequest                           │ 可视化数据提取通用请求                   │ -         │
│ dto/FuncDetailResponse.java            │ class FuncDetailResponse                       │ 单功能详情响应（中间核心区）             │ -         │
│ dto/FuncPerfResponse.java              │ class FuncPerfResponse                         │ 功能表现 × 场景交叉分析响应（横向柱状图） │ -         │
│ dto/GpsBbox.java                       │ class GpsBbox                                  │ GPS 地理包围盒，WGS84 坐标系             │ -         │
│ dto/MapFrameResponse.java              │ class MapFrameResponse                         │ 一帧 ddld 地图数据（窗口内最近一帧聚合，landmark_vis） │ -         │
│ dto/ObstacleFrameResponse.java         │ class ObstacleFrameResponse                    │ 障碍物帧（窗口内最近一帧聚合）           │ -         │
│ dto/ObstacleQueryResult.java           │ class ObstacleQueryResult                      │ 障碍物查询结果（Controller 路由用）      │ -         │
│ dto/PerceptionFrameResponse.java       │ class PerceptionFrameResponse                  │ 融合感知目标帧（窗口内最近一帧聚合，smooth_object） │ -         │
│ dto/PriorityUpdateRequest.java         │ class PriorityUpdateRequest                    │ 场景规则优先级批量更新请求项             │ -         │
│ dto/RawMessagePageResponse.java        │ class RawMessagePageResponse                   │ Raw 消息分页响应                         │ -         │
│ dto/RecentUploadsResponse.java         │ class RecentUploadsResponse                    │ 最近上传列表响应（分页）                 │ -         │
│ dto/ReplayContextResponse.java         │ class ReplayContextResponse                    │ 2D 回放页初始化上下文                    │ -         │
│ dto/ReplayExtractionRequest.java       │ class ReplayExtractionRequest                  │ 回放提取任务请求                         │ -         │
│ dto/ReplayExtractionResponse.java      │ class ReplayExtractionResponse                 │ 提取任务创建响应（202）                  │ -         │
│ dto/ReplayExtractionTaskResponse.java  │ class ReplayExtractionTaskResponse             │ 提取任务详情                             │ -         │
│ dto/ReplayFrameQueryResult.java        │ class ReplayFrameQueryResult                   │ 回放帧查询结果（Controller 路由用，泛型化 ObstacleQueryResult） │ -         │
│ dto/SceneDistResponse.java             │ class SceneDistResponse                        │ 场景类型分布响应（横向柱状图）           │ -         │
│ dto/SceneExtractionResponse.java       │ class SceneExtractionResponse                  │ 场景提取任务创建响应（202）              │ -         │
│ dto/SceneExtractionTaskResponse.java   │ class SceneExtractionTaskResponse              │ 场景提取任务详情                         │ -         │
│ dto/SceneListResponse.java             │ class SceneListResponse                        │ 切片场景列表响应（分页）                 │ -         │
│ dto/SceneRuleConfigUpdateRequest.java  │ class SceneRuleConfigUpdateRequest             │ 场景规则阈值配置更新请求                 │ -         │
│ dto/SceneRuleCreateRequest.java        │ class SceneRuleCreateRequest                   │ 场景规则创建请求                         │ -         │
│ dto/SceneRuleDTO.java                  │ class SceneRuleDTO                             │ 场景规则响应 DTO                         │ -         │
│ dto/SceneRuleUpdateRequest.java        │ class SceneRuleUpdateRequest                   │ 场景规则更新请求                         │ -         │
│ dto/SensorCalibrationRequest.java      │ class SensorCalibrationRequest                 │ 传感器标定摄入请求（按 slice_id+sensor_name upsert） │ -         │
│ dto/SensorCalibrationResponse.java     │ class SensorCalibrationResponse                │ 传感器标定记录                           │ -         │
│ dto/SensorCalibrationSet.java          │ class SensorCalibrationSet                     │ 传感器标定集合（含坐标系/单位声明与车体尺寸） │ -         │
│ dto/SignalDataResponse.java            │ class SignalDataResponse                       │ 信号提取任务结果响应                     │ -         │
│ dto/SliceDetailResponse.java           │ class SliceDetailResponse                      │ 切片详情响应（含 SensorStream 列表）     │ -         │
│ dto/SliceListResponse.java             │ class SliceListResponse                        │ 切片列表响应（分页）                     │ -         │
│ dto/SliceSearchRequest.java            │ class SliceSearchRequest                       │ 切片检索参数                             │ -         │
│ dto/SliceStatsResponse.java            │ class SliceStatsResponse                       │ 切片统计指标响应 DTO.                    │ -         │
│ dto/SliceTagResponse.java              │ class SliceTagResponse                         │ 切片标签响应                             │ -         │
│ dto/TagRequest.java                    │ class TagRequest                               │ 标签创建/更新请求                        │ -         │
│ dto/TakeoverReasonsResponse.java       │ class TakeoverReasonsResponse                  │ 接管/介入原因分布响应（环形饼图）        │ -         │
│ dto/TaskResponse.java                  │ class TaskResponse                             │ 导入任务响应（含状态历史）               │ -         │
│ dto/Top5Response.java                  │ class Top5Response                             │ 工况/场景 TOP5 排行榜响应                │ -         │
│ dto/TrajectoryQueryResult.java         │ class TrajectoryQueryResult                    │ GPS 轨迹查询结果                         │ -         │
│ dto/TrajectoryResponse.java            │ class TrajectoryResponse                       │ GPS 轨迹响应                             │ -         │
│ dto/VehicleSpec.java                   │ class VehicleSpec                              │ 车体尺寸（自车轮廓绘制）                 │ -         │
│ dto/VehicleTypesResponse.java          │ class VehicleTypesResponse                     │ 车型下拉选项响应                         │ -         │
│ dto/VideoStreamResponse.java           │ class VideoStreamResponse                      │ 视频流列表响应                           │ -         │
└────────────────────────────────────────┴────────────────────────────────────────────────┴──────────────────────────────────────────┴───────────┘

## Config 层

> Spring 配置（@Configuration）（11 个文件）

┌──────────────────────────────────────┬──────────────────────────────────────────────────────────┬──────────────────────────────────────────┬───────────┐
│ 文件                                 │ 关键信息                                                 │ 描述                                     │ @see 依赖 │
├──────────────────────────────────────┼──────────────────────────────────────────────────────────┼──────────────────────────────────────────┼───────────┤
│ config/AsyncConfig.java              │ class AsyncConfig implements AsyncConfigurer             │ 异步任务线程池配置                       │ -         │
│ config/BosConfig.java                │ class BosConfig                                          │ 百度 BOS 对象存储客户端配置              │ -         │
│ config/DataFilePathRepairRunner.java │ class DataFilePathRepairRunner implements ApplicationRunner │ 启动时数据修复：修正 data_slice.file_path 指向失效文件的存量数据 │ -         │
│ config/FeignConfig.java              │ class FeignConfig                                        │ Feign 客户端配置                         │ -         │
│ config/KafkaConfig.java              │ class KafkaConfig                                        │ Kafka Producer 配置                      │ -         │
│ config/MinioConfig.java              │ class MinioConfig                                        │ MinIO 对象存储客户端配置（MinIO 迁移）   │ -         │
│ config/MyMetaObjectHandler.java      │ class MyMetaObjectHandler implements MetaObjectHandler   │ MyBatis-Plus 自动填充处理器              │ -         │
│ config/MybatisPlusConfig.java        │ class MybatisPlusConfig                                  │ MyBatis-Plus 配置                        │ -         │
│ config/SecurityConfig.java           │ class SecurityConfig                                     │ IAMS 统一鉴权接入 —— Spring Security OAuth2 Resource Server │ -         │
│ config/StorageConfig.java            │ class StorageConfig                                      │ 存储配置（MinIO 迁移）                   │ -         │
│ config/WebMvcConfig.java             │ class WebMvcConfig implements WebMvcConfigurer           │ Web MVC 配置                             │ -         │
└──────────────────────────────────────┴──────────────────────────────────────────────────────────┴──────────────────────────────────────────┴───────────┘

## Util 层

> 工具类（7 个文件）

┌───────────────────────────┬───────────────────────┬──────────────────────────────────────────┬───────────┐
│ 文件                      │ 关键信息              │ 描述                                     │ @see 依赖 │
├───────────────────────────┼───────────────────────┼──────────────────────────────────────────┼───────────┤
│ util/CfdiConstants.java   │ class CfdiConstants   │ CFDI 模块共享常量                        │ -         │
│ util/DockerExecutor.java  │ class DockerExecutor  │ Docker 容器执行器                        │ -         │
│ util/PythonExecutor.java  │ class PythonExecutor  │ Python 算法引擎执行器                    │ -         │
│ util/ReplayTimeUtil.java  │ class ReplayTimeUtil  │ 回放时间工具（bag 时间戳 ⇄ RFC3339 ⇄ 纳秒） │ -         │
│ util/ReplayTopicUtil.java │ class ReplayTopicUtil │ 回放 topic 工具                          │ -         │
│ util/StorageKeys.java     │ class StorageKeys     │ 存储 Key 规范（MinIO 迁移）              │ -         │
│ util/TraceIdUtil.java     │ class TraceIdUtil     │ 链路追踪 ID 工具                         │ -         │
└───────────────────────────┴───────────────────────┴──────────────────────────────────────────┴───────────┘

## Event 层

> Kafka 事件（Payload / 发布器）（3 个文件）

┌────────────────────────────────┬───────────────────────────┬────────────────────────────┬───────────┐
│ 文件                           │ 关键信息                  │ 描述                       │ @see 依赖 │
├────────────────────────────────┼───────────────────────────┼────────────────────────────┼───────────┤
│ event/SliceCreatedEvent.java   │ class SliceCreatedEvent   │ 数据切片已创建事件 Payload │ -         │
│ event/SliceDeletedEvent.java   │ class SliceDeletedEvent   │ 数据切片已删除事件 Payload │ -         │
│ event/SliceEventPublisher.java │ class SliceEventPublisher │ 数据切片生命周期事件推送器 │ -         │
└────────────────────────────────┴───────────────────────────┴────────────────────────────┴───────────┘

## Exception 层

> 异常定义（6 个文件）

┌────────────────────────────────────────┬──────────────────────────────────────────────────────────┬─────────────────────────────────────────┬───────────┐
│ 文件                                   │ 关键信息                                                 │ 描述                                    │ @see 依赖 │
├────────────────────────────────────────┼──────────────────────────────────────────────────────────┼─────────────────────────────────────────┼───────────┤
│ exception/SceneRuleConfigNotFoundException.java │ class SceneRuleConfigNotFoundException extends RuntimeException │ 场景规则阈值配置不存在异常（Review W1 模式） │ -         │
│ exception/SceneRuleNotFoundException.java │ class SceneRuleNotFoundException extends RuntimeException │ 场景规则不存在异常（Review W1 模式）    │ -         │
│ exception/SliceNotFoundException.java  │ class SliceNotFoundException extends RuntimeException    │ 切片不存在异常（Review W1）             │ -         │
│ exception/StorageArchivedException.java │ class StorageArchivedException extends StorageException  │ 数据已归档不可访问异常（MinIO 迁移）    │ -         │
│ exception/StorageException.java        │ class StorageException extends RuntimeException          │ 存储层通用异常（MinIO 迁移）            │ -         │
│ exception/StorageNotFoundException.java │ class StorageNotFoundException extends StorageException  │ 存储对象不存在异常（MinIO 迁移）        │ -         │
└────────────────────────────────────────┴──────────────────────────────────────────────────────────┴─────────────────────────────────────────┴───────────┘

## Test 层

> 单元/集成测试（34 个文件）

┌────────────────────────────────────────┬───────────────────────────────────────────────┬──────────────────────────────────────────┬───────────┐
│ 文件                                   │ 关键信息                                      │ 描述                                     │ @see 依赖 │
├────────────────────────────────────────┼───────────────────────────────────────────────┼──────────────────────────────────────────┼───────────┤
│ StorageModeContextLoadTest.java        │ class StorageModeContextLoadTest              │ 存储模式装配矩阵测试（Review W1 修复验证） │ -         │
│ controller/DashboardControllerTest.java │ class DashboardControllerTest                 │ -                                        │ -         │
│ controller/DataSliceControllerTest.java │ class DataSliceControllerTest                 │ -                                        │ -         │
│ controller/ExportControllerTest.java   │ class ExportControllerTest                    │ -                                        │ -         │
│ controller/ReplayControllerTest.java   │ class ReplayControllerTest                    │ 2D 回放 Controller 测试（契约 v1.3.0：200/202/404/409/422 路由与统一错误结构） │ -         │
│ controller/SceneExtractionSecurityHttpTest.java │ class SceneExtractionSecurityHttpTest         │ SceneExtractionController.readProcessFile 编码变体攻击 MockMvc 层回归（Review S1 补强） │ -         │
│ controller/TagControllerTest.java      │ class TagControllerTest                       │ -                                        │ -         │
│ controller/TaskControllerTest.java     │ class TaskControllerTest                      │ -                                        │ -         │
│ controller/TrajectoryControllerTest.java │ class TrajectoryControllerTest                │ -                                        │ -         │
│ controller/VideoStreamingControllerTest.java │ class VideoStreamingControllerTest            │ VideoStreamingController 目录穿越回归测试（S2） │ -         │
│ controller/VisualizationControllerTest.java │ class VisualizationControllerTest             │ -                                        │ -         │
│ integration/DataSliceIntegrationTest.java │ class DataSliceIntegrationTest                │ Full-stack integration test for the DIMS data ingestion pipeline. │ -         │
│ integration/DatabaseMigrationValidationTest.java │ class DatabaseMigrationValidationTest         │ 数据库迁移文件 V2__phase2_new_tables.sql 验证测试 │ -         │
│ integration/MinioStorageServiceIntegrationTest.java │ class MinioStorageServiceIntegrationTest      │ MinIO 真实连通性冒烟测试（MinIO 迁移）   │ -         │
│ integration/PythonExecutorIntegrationTest.java │ class PythonExecutorIntegrationTest           │ Integration test for PythonExecutor using real ROS1 bag files. │ -         │
│ integration/StorageArchivedAccessHttpTest.java │ class StorageArchivedAccessHttpTest           │ 归档切片拦截 HTTP 层测试（MinIO 迁移）   │ -         │
│ security/JwtAuthoritiesConverterTest.java │ class JwtAuthoritiesConverterTest             │ JwtAuthoritiesConverter 单元测试         │ -         │
│ security/ResourceServerSecurityTest.java │ class ResourceServerSecurityTest              │ IAMS 鉴权接入测试（加载真实 SecurityFilterChain + @EnableMethodSecurity） │ -         │
│ security/SliceSecurityTestConfig.java  │ class SliceSecurityTestConfig                 │ {@code @WebMvcTest} 切片测试专用安全配置 │ -         │
│ security/TestProfileSecurityConfig.java │ class TestProfileSecurityConfig               │ {@code @SpringBootTest} + profile=test 集成测试（如 DataSliceIntegrationTest） │ -         │
│ service/CalibrationServiceImplTest.java │ class CalibrationServiceImplTest              │ 传感器标定 Service 测试                  │ -         │
│ service/DashboardServiceImplTest.java  │ class DashboardServiceImplTest                │ -                                        │ -         │
│ service/DataSliceServiceImplTest.java  │ class DataSliceServiceImplTest                │ -                                        │ -         │
│ service/ExportServiceImplTest.java     │ class ExportServiceImplTest                   │ -                                        │ -         │
│ service/IngestionPipelineServiceImplTest.java │ class IngestionPipelineServiceImplTest        │ stub 统计/场景提取为立即 SUCCESS（同步等待阶段的轮询首次即命中终态） │ -         │
│ service/LocalStorageServiceTest.java   │ class LocalStorageServiceTest                 │ LocalStorageService 存储层防线测试（Review S1 第二层 + W5 行为） │ -         │
│ service/ReplayExtractionPipelineServiceImplTest.java │ class ReplayExtractionPipelineServiceImplTest │ 回放提取流水线测试（标定导出格式防回归） │ -         │
│ service/ReplayExtractionTaskServiceImplTest.java │ class ReplayExtractionTaskServiceImplTest     │ 回放提取任务 Service 测试                │ -         │
│ service/ReplayServiceImplCacheTest.java │ class ReplayServiceImplCacheTest              │ 回放 Service 缓存读取测试                │ -         │
│ service/SceneExtractionPipelineServiceImplTest.java │ class SceneExtractionPipelineServiceImplTest  │ SceneExtractionPipelineServiceImpl.readProcessFile 安全校验测试（Review S1） │ -         │
│ service/TagServiceImplTest.java        │ class TagServiceImplTest                      │ -                                        │ -         │
│ service/TrajectoryServiceImplTest.java │ class TrajectoryServiceImplTest               │ TrajectoryServiceImpl 单元测试           │ -         │
│ service/VisualizationPipelineServiceImplTest.java │ class VisualizationPipelineServiceImplTest    │ -                                        │ -         │
│ service/VisualizationServiceImplTest.java │ class VisualizationServiceImplTest            │ VisualizationServiceImpl 单元测试（Review W3 补测） │ -         │
└────────────────────────────────────────┴───────────────────────────────────────────────┴──────────────────────────────────────────┴───────────┘

## 功能域依赖链

> 由项目内 import 依赖机械推导：Controller → Service 接口 → Service 实现 → Mapper → Entity

```
CalibrationController → CalibrationService → CalibrationServiceImpl → CameraCalibration
CockpitStatsController → DashboardStatsPipelineService → DashboardStatsPipelineServiceImpl → DashboardStatsTask
DashboardController → DashboardService → DashboardServiceImpl → CameraCalibration
DataSliceController → DataSliceService → DataSliceServiceImpl → CfdiPipelineService → CfdiPipelineServiceImpl → CfdiConversionService → CfdiConversionServiceImpl
ExportController → ExportService → ExportServiceImpl → ExportPipelineService → ExportPipelineServiceImpl → ExportTask
ReplayController → ReplayExtractionTaskService → ReplayExtractionTaskServiceImpl → ReplayExtractionPipelineService → ReplayExtractionPipelineServiceImpl → ReplayExtractionTask
ReplayExtractionController → ReplayExtractionTaskService → ReplayExtractionTaskServiceImpl → ReplayExtractionPipelineService → ReplayExtractionPipelineServiceImpl → ReplayExtractionTask
SceneExtractionController → SceneExtractionPipelineService → SceneExtractionPipelineServiceImpl → SceneRuleService → SceneRuleServiceImpl → SceneRule
SceneRuleController → SceneRuleService → SceneRuleServiceImpl → SceneRule
TagController → TagService → TagServiceImpl → DataSliceTag
TaskController → IngestionService → IngestionServiceImpl → IngestionTask
TrajectoryController → TrajectoryService → TrajectoryServiceImpl → VisualizationPipelineService → VisualizationPipelineServiceImpl → GpsTrajectoryService → GpsTrajectoryServiceImpl
VideoStreamingController → StorageStat
VisualizationController → VisualizationPipelineService → VisualizationPipelineServiceImpl → GpsTrajectoryService → GpsTrajectoryServiceImpl → GpsTrajectory
```
