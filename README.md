# DIMS Backend — dims-server

> Data Ingestion & Management Subsystem 后端服务
> 基于实际代码（v1.0.0-SNAPSHOT）整理，与设计文档可能存在偏差。

---

## 目录

1. [环境要求](#1-环境要求)
2. [编译与启动](#2-编译与启动)
3. [配置项](#3-配置项)
4. [数据库表结构](#4-数据库表结构)
5. [API 端点清单](#5-api-端点清单)
6. [功能模块](#6-功能模块)
7. [异步流水线流程](#7-异步流水线流程)
8. [Kafka 事件](#8-kafka-事件)

---

## 1. 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | Spring Boot 3.2.5 要求 |
| Maven | 3.6+ | 构建工具 |
| MySQL | 8.0+ | 数据源，数据库名 `dims` |
| Python | 3.x | 调用解析/打标/导出脚本（可选，流水线依赖） |
| Kafka | 3.x | 事件消息队列（可选，无 Kafka 时降级为日志） |

---

## 2. 编译与启动

### 2.1 编译

```bash
cd dims-server
mvn clean package -DskipTests
```

编译产物：`target/dims-1.0.0-SNAPSHOT.jar`

### 2.2 启动

```bash
# 使用默认配置（application.yml）
java -jar target/dims-1.0.0-SNAPSHOT.jar

# 使用开发环境配置
DIMS_SECURITY_DEV_BYPASS=true java -jar target/dims-1.0.0-SNAPSHOT.jar
java -jar target/dims-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
/home/nnn/project/esmini/build/EnvironmentSimulator/Applications/esmini/esmini --odr /home/nnn/project/JMC/isim-dims/data/scene_data/123/direct_reconstruction/123.xodr --osc /home/nnn/project/JMC/isim-dims/data/scene_data/123/direct_reconstruction/123.xosc --window 1240 720
#获取token
curl -i -sS -X POST   'http://iamsbackend.jmc.svc:8080/iams/oauth2/token'   -u 'isim-dims:isim-dims'   -H 'Content-Type: application/x-www-form-urlencoded'   --data-urlencode 'grant_type=client_credentials'

# 直接通过 Maven 启动（开发期间）
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 2.3 健康检查

服务启动后，访问 `GET http://localhost:8080/v1/health` 确认状态。

### 2.4 注意事项

- 启动前需确保 MySQL 中已创建 `dims` 数据库，表结构通过手动 DDL 或 FlyWay/Liquibase 创建（当前代码未包含自动建表）。
- Kafka 不可用时，事件发送降级为日志，不影响主流程。
- Python 脚本目录默认指向 `../dims-python`，启动前需确保该目录存在且有可执行脚本。

---

## 3. 配置项

### 3.1 application.yml 关键配置

```yaml
server:
  port: 8080

spring:
  servlet:
    multipart:
      max-file-size: 500MB        # 单文件大小上限
      max-request-size: 500MB     # 请求体大小上限

  jackson:
    property-naming-strategy: SNAKE_CASE   # 全局 snake_case，对齐 OpenAPI 契约

  datasource:
    url: jdbc:mysql://localhost:3306/dims
    driver-class-name: com.mysql.cj.jdbc.Driver
    username: root
    password: root

  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.apache.kafka.common.serialization.StringSerializer

mybatis-plus:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true
    log-impl: org.apache.ibatis.logging.stdout.StdOutImpl

dims:
  storage:
    bag-dir: /data/bags                # ROS bag 原始文件存储目录
    export-dir: /data/exports          # 导出产物存储目录
  python:
    script-dir: ../dims-python          # Python 算法脚本目录
    timeout-sec: 300                    # Python 脚本执行超时（秒）
```

### 3.2 application-dev.yml（开发环境覆盖）

```yaml
spring:
  datasource:
    password: root123

dims:
  storage:
    bag-dir: /home/nnn/project/JMC/isim-dims/dims-server/data/bags
    export-dir: /home/nnn/project/JMC/isim-dims/dims-server/data/exports

logging:
  level:
    com.isim.dims: DEBUG
```

### 3.3 Kafka Producer 参数（代码中 KafkaConfig 配置）

| 参数 | 值 | 说明 |
|------|-----|------|
| `acks` | `1` | Leader 写入即确认 |
| `retries` | `3` | 发送失败重试 3 次 |
| `batch.size` | `16384` (16KB) | 批次缓冲大小 |

KafkaConfig 标注了 `@ConditionalOnProperty(name = "spring.kafka.bootstrap-servers")`，未配置 Kafka 地址时自动禁用。

### 3.4 异步线程池参数（AsyncConfig）

| 参数 | 值 |
|------|-----|
| corePoolSize | 2 |
| maxPoolSize | 4 |
| queueCapacity | 100 |
| RejectedExecutionHandler | CallerRunsPolicy |
| 线程名前缀 | `dims-async-` |

---

## 4. 数据库表结构

基于 Entity 代码提取。数据库中需预先创建 `dims` 库，表需手动创建。

### 4.1 data_slice — 数据切片主表

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VARCHAR(32) | 主键，UUID，ASSIGN_UUID 策略 |
| `name` | VARCHAR | 切片名称 |
| `file_path` | VARCHAR | 原始 bag 文件绝对路径 |
| `file_size` | BIGINT | 文件字节数 |
| `start_ts` | DATETIME | 数据起始时间，UTC |
| `end_ts` | DATETIME | 数据结束时间，UTC |
| `duration_ms` | BIGINT | 数据时长（毫秒） |
| `gps_min_lat` | DOUBLE | GPS 包围盒最小纬度，WGS84 |
| `gps_min_lon` | DOUBLE | GPS 包围盒最小经度，WGS84 |
| `gps_max_lat` | DOUBLE | GPS 包围盒最大纬度，WGS84 |
| `gps_max_lon` | DOUBLE | GPS 包围盒最大经度，WGS84 |
| `sensor_types` | VARCHAR/TEXT (JSON) | JSON 数组字符串，如 `["camera","lidar"]` |
| `msg_count_total` | BIGINT | 总消息数 |
| `status` | VARCHAR | `UPLOADING \| PARSING \| TAGGING \| READY \| ERROR` |
| `error_msg` | VARCHAR | 错误信息 |
| `created_at` | DATETIME | 创建时间，**自动填充** |
| `updated_at` | DATETIME | 更新时间，**自动填充** |

### 4.2 ingestion_task — 导入任务表

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VARCHAR(32) | 主键，UUID |
| `slice_id` | VARCHAR(32) | 关联切片 ID |
| `status` | VARCHAR | `UPLOADING \| PARSING \| TAGGING \| READY \| ERROR` |
| `progress` | INT | 进度百分比 0-100 |
| `error_msg` | VARCHAR | 错误信息 |
| `created_at` | DATETIME | 创建时间，**自动填充** |
| `updated_at` | DATETIME | 更新时间，**自动填充** |

> 与 `data_slice` 为一对一关系。

### 4.3 tag — 标签库表

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VARCHAR(32) | 主键，UUID |
| `name` | VARCHAR | 标签名 |
| `category` | VARCHAR | 分类路径，如 `场景/道路` |
| `description` | VARCHAR | 标签说明 |
| `rule_expr` | VARCHAR | 自动打标规则表达式（Phase 2+ DSL） |
| `created_at` | DATETIME | 创建时间，**自动填充** |

### 4.4 data_slice_tag — 切片-标签关联表

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VARCHAR(32) | 主键，UUID |
| `slice_id` | VARCHAR(32) | 切片 ID |
| `tag_id` | VARCHAR(32) | 标签 ID |
| `source` | VARCHAR | `auto \| manual`，自动打标或手动关联 |

### 4.5 sensor_stream — 传感器流索引表

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VARCHAR(32) | 主键，UUID |
| `slice_id` | VARCHAR(32) | 关联切片 ID |
| `topic` | VARCHAR | ROS Topic 完整路径 |
| `msg_type` | VARCHAR | ROS 消息类型 |
| `sensor_category` | VARCHAR | `camera \| lidar \| radar \| gnss_imu \| vehicle_signal \| ultrasonic \| adas \| other` |
| `msg_count` | INT | 该 Topic 消息总数 |
| `frequency_hz` | DOUBLE | 平均频率 |
| `first_ts` | DATETIME | 首条消息时间 |
| `last_ts` | DATETIME | 末条消息时间 |
| `metadata` | TEXT (JSON) | 额外元数据（JSON 对象字符串） |

### 4.6 export_task — 导出任务表

| 字段 | 类型 | 说明 |
|------|------|------|
| `id` | VARCHAR(32) | 主键，UUID |
| `slice_id` | VARCHAR(32) | 关联切片 ID |
| `start_ts` | DATETIME | 导出时间窗口起点 |
| `end_ts` | DATETIME | 导出时间窗口终点 |
| `topics` | TEXT (JSON) | 导出的 Topic 列表（JSON 数组），空表示全部 |
| `format` | VARCHAR | `bag \| mp4 \| images \| json` |
| `file_path` | VARCHAR | 导出文件路径 |
| `file_size` | BIGINT | 导出文件字节数 |
| `status` | VARCHAR | `PENDING \| PROCESSING \| READY \| ERROR \| EXPIRED` |
| `expires_at` | DATETIME | 下载链接过期时间（默认 created_at + 24h） |
| `created_at` | DATETIME | 创建时间，**自动填充** |

### 4.7 自动填充字段

由 `MyMetaObjectHandler` 实现 MyBatis-Plus `MetaObjectHandler` 接口：

| 实体 | 自动填充字段 | 触发时机 |
|------|-------------|---------|
| `DataSlice` | `createdAt`, `updatedAt` | `INSERT` |
| `DataSlice` | `updatedAt` | `UPDATE` |
| `IngestionTask` | `createdAt`, `updatedAt` | `INSERT` |
| `IngestionTask` | `updatedAt` | `UPDATE` |
| `Tag` | `createdAt` | `INSERT` |
| `ExportTask` | `createdAt` | `INSERT` |

---

## 5. API 端点清单

所有端点基于 Controller 类注解和 @RequestMapping 提取。统一前缀遵循 OpenAPI。

### 5.1 DataSliceController — 数据切片管理

**路由前缀**: `/v1/data-slices`

| 方法 | 路径 | 状态码 | 描述 | 请求参数 | 响应 |
|------|------|--------|------|---------|------|
| `POST` | `/v1/data-slices` | `202` | 上传 ROS1 bag 文件，创建切片 | `file` (MultipartFile, 必填), `name` (String, 可选) | `{ slice_id, task_id, status }` |
| | | `500` | 上传失败 | | `{ error: { code: "UPLOAD_FAILED", message, trace_id } }` |
| `GET` | `/v1/data-slices` | `200` | 多条件检索（分页） | 见下方查询参数表 | `SliceListResponse` |
| | | `500` | 搜索失败 | | `{ error: { code: "SEARCH_FAILED", message, trace_id } }` |
| `GET` | `/v1/data-slices/{sliceId}` | `200` | 获取切片详情（含 SensorStream） | `sliceId` (Path) | `SliceDetailResponse` |
| | | `500` | 切片不存在 | | `{ error: { code: "SLICE_NOT_FOUND", message, trace_id } }` |
| `DELETE` | `/v1/data-slices/{sliceId}` | `204` | 删除切片（文件+DB+Kafka事件） | `sliceId` (Path) | 无内容 |
| | | `500` | 删除失败 | | `{ error: { code: "SLICE_NOT_FOUND", message, trace_id } }` |

**POST /v1/data-slices 请求**: `Content-Type: multipart/form-data`

**GET /v1/data-slices 查询参数**（snake_case）：

| 参数 | 类型 | 必填 | 默认 | 说明 |
|------|------|------|------|------|
| `start_ts` | String | 否 | - | 数据起始时间，RFC3339 |
| `end_ts` | String | 否 | - | 数据结束时间，RFC3339 |
| `sensor_types` | String | 否 | - | 逗号分隔传感器类型 |
| `tags` | String | 否 | - | 逗号分隔标签 ID（全匹配 AND） |
| `lat` | Double | 否 | - | GPS 中心纬度，WGS84 |
| `lon` | Double | 否 | - | GPS 中心经度，WGS84 |
| `radius` | Integer | 否 | - | GPS 搜索半径（米） |
| `keyword` | String | 否 | - | 名称模糊搜索 |
| `status` | String | 否 | - | 状态过滤：`READY` \| `ERROR` |
| `page` | Integer | 否 | `1` | 页码（从 1 开始） |
| `page_size` | Integer | 否 | `50` | 每页条数（最大 500） |
| `sort` | String | 否 | `created_at:desc` | 排序 `field:direction`，白名单：`created_at`, `start_ts`, `end_ts`, `file_size`, `name` |

**所有端点共用请求头**：

| Header | 说明 |
|--------|------|
| `X-Trace-Id` | 链路追踪 ID，可选，默认 `auto-generated` |

### 5.2 TagController — 标签管理

**路由前缀**: `/v1`

| 方法 | 路径 | 状态码 | 描述 | 请求参数 | 响应 |
|------|------|--------|------|---------|------|
| `GET` | `/v1/tags` | `200` | 获取标签库列表 | `category` (Query, 可选) | `Tag[]` |
| | | `500` | 查询失败 | | `{ error: { code: "TAG_LIST_FAILED", message, trace_id } }` |
| `POST` | `/v1/tags` | `201` | 创建标签 | 请求体 `TagRequest` (JSON) | 创建的 `Tag` |
| | | `500` | 创建失败 | | `{ error: { code: "TAG_CREATE_FAILED", message, trace_id } }` |
| `PUT` | `/v1/tags/{tagId}` | `200` | 修改标签 | `tagId` (Path), `TagRequest` (JSON) | 更新后的 `Tag` |
| | | `500` | 修改失败 | | `{ error: { code: "TAG_UPDATE_FAILED", message, trace_id } }` |
| `DELETE` | `/v1/tags/{tagId}` | `204` | 删除标签 | `tagId` (Path) | 无内容 |
| | | `500` | 删除失败 | | `{ error: { code: "TAG_DELETE_FAILED", message, trace_id } }` |
| `GET` | `/v1/data-slices/{sliceId}/tags` | `200` | 查询切片关联标签 | `sliceId` (Path) | `Tag[]` |
| | | `500` | 查询失败 | | `{ error: { code: "SLICE_TAGS_FAILED", message, trace_id } }` |
| `PUT` | `/v1/data-slices/{sliceId}/tags` | `204` | 批量设置切片标签（覆盖式） | `sliceId` (Path), `{ tag_ids, source }` (JSON) | 无内容 |
| | | `500` | 设置失败 | | `{ error: { code: "SLICE_TAGS_SET_FAILED", message, trace_id } }` |

**TagRequest 请求体**：

```json
{
  "name": "string",
  "category": "string",
  "description": "string",
  "rule_expr": "string"
}
```

**PUT /v1/data-slices/{sliceId}/tags 请求体**：

```json
{
  "tag_ids": ["uuid1", "uuid2"],
  "source": "auto|manual"
}
```

### 5.3 ExportController — 数据导出

| 方法 | 路径 | 状态码 | 描述 | 请求参数 | 响应 |
|------|------|--------|------|---------|------|
| `POST` | `/v1/data-slices/{sliceId}/exports` | `202` | 创建导出任务 | `sliceId` (Path), `ExportRequest` (JSON) | `ExportResponse` |
| | | `500` | 导出失败 | | `{ error: { code: "EXPORT_FAILED", message, trace_id } }` |
| `GET` | `/v1/exports/{exportId}` | `200` | 查询导出状态和下载链接 | `exportId` (Path) | `ExportResponse` |
| | | `500` | 查询失败 | | `{ error: { code: "EXPORT_NOT_FOUND", message, trace_id } }` |

**ExportRequest 请求体**：

```json
{
  "start_ts": "2026-07-24T09:00:00Z",
  "end_ts": "2026-07-24T09:30:00Z",
  "topics": ["/sensor/camera_front_wide/video"],
  "format": "bag"
}
```

**ExportResponse 响应**：

```json
{
  "export_id": "uuid",
  "slice_id": "uuid",
  "status": "PENDING|PROCESSING|READY|ERROR|EXPIRED",
  "format": "bag",
  "file_size": 123456,
  "download_url": "/data/exports/.../output.bag",
  "expires_at": "2026-07-25T10:00:00Z",
  "created_at": "2026-07-24T10:00:00Z"
}
```

### 5.4 TaskController — 任务查询与健康检查

| 方法 | 路径 | 状态码 | 描述 | 请求参数 | 响应 |
|------|------|--------|------|---------|------|
| `GET` | `/v1/tasks/{taskId}` | `200` | 查询导入任务进度 | `taskId` (Path) | `IngestionTask` |
| | | `500` | 任务不存在 | | `{ error: { code: "TASK_NOT_FOUND", message, trace_id } }` |
| `GET` | `/v1/health` | `200` | 健康检查 | 无 | `{ status, service, timestamp }` |

### 5.5 统一错误结构

所有错误响应遵循：

```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "Human readable message",
    "trace_id": "auto-generated"
  }
}
```

---

## 6. 功能模块

### 6.1 数据导入模块（DataSliceService + IngestionPipelineService）

**核心类**:
- `DataSliceServiceImpl` — 上传、检索、详情、删除
- `IngestionPipelineServiceImpl` — 异步解析与自动打标流水线

**上传流程**:
1. 接收 MultipartFile bag 文件
2. 在 `{bag-dir}/{sliceId}/original.bag` 保存文件
3. 创建 `data_slice` 记录（status=UPLOADING）
4. 创建 `ingestion_task` 记录（status=UPLOADING, progress=0）
5. 委派 `IngestionPipelineService.processParsePipeline()` 异步执行

**多条件检索**（`searchSlices` XML SQL 实现）:
- 状态精确匹配
- 时间范围闭区间（start_ts >=, end_ts <=）
- 传感器类型 JSON 包含匹配（`JSON_CONTAINS`）
- GPS 包围盒重叠检测（4 个边界字段）
- 关键字 name LIKE 模糊搜索
- 标签全匹配子查询（HAVING COUNT = 参数个数，AND 逻辑）
- 排序白名单校验防止 SQL 注入（只允许 `created_at`, `start_ts`, `end_ts`, `file_size`, `name` 列和 `ASC`/`DESC` 方向）

**删除流程**:
1. 删除原始 bag 文件及空目录
2. 清空关联导出产物目录
3. 级联删除 `sensor_stream`, `data_slice_tag`, `ingestion_task`, `data_slice` 记录
4. 记录 Kafka 事件日志（当前以 log 模拟）

### 6.2 标签管理模块（TagService + TagController）

**核心类**: `TagServiceImpl`

**功能**:
- **标签库 CRUD**: 按分类路径过滤列表查询、创建（全字段写入）、部分字段更新（仅更新非空字段）、删除（自动级联清理关联）
- **切片标签管理**: 查询切片关联标签（`data_slice_tag` INNER JOIN `tag`）、覆盖式批量设置（先删后插，支持 source=auto|manual）

### 6.3 数据导出模块（ExportService + ExportPipelineService）

**核心类**:
- `ExportServiceImpl` — 创建导出任务、查询导出状态
- `ExportPipelineServiceImpl` — 异步导出流水线

**导出流程**:
1. 验证切片存在且状态为 READY
2. 检查是否存在相同参数的有效导出（相同 sliceId + startTs + endTs + topics + format + 未过期），缓存复用
3. 创建 `export_task` 记录（status=PENDING, expires_at=created_at+24h）
4. 委派 `ExportPipelineService.processExportPipeline()` 异步执行
5. 异步流水线调用 Python `extract_export.py` 裁剪导出
6. 导出产物保存在 `{export-dir}/{sliceId}/{exportId}/output.bag`

### 6.4 健康检查

`GET /v1/health` 返回 JSON：

```json
{
  "status": "UP",
  "service": "dims",
  "timestamp": "2026-07-25T10:00:00Z"
}
```

### 6.5 Python 引擎执行器（PythonExecutor）

**核心类**: `PythonExecutor`

**调用方式**: `ProcessBuilder` 启动 `python3 {scriptName} --input {tempJsonFile}`

**流程**:
1. 参数序列化为临时 JSON 文件
2. 在工作目录 `{scriptDir}` 中执行 Python 脚本
3. 等待完成（可配置超时，默认 300 秒）
4. 检查退出码（非零抛出异常）
5. 读取 stdout 作为 JSON 结果
6. 自动清理临时文件

**注入到脚本的参数**（通过 --input 文件传递 JSON）：

| 脚本 | 场景 | 调用参数 |
|------|------|---------|
| `parse_rosbag.py` | 导入解析 | `{ bag_path: string }` |
| `auto_tag.py` | 自动打标 | `{ slice_id, gps_bbox, start_ts, end_ts, sensor_types }` |
| `extract_export.py` | 导出裁剪 | `{ bag_path, output_path, start_ts, end_ts, topics, format }` |

---

## 7. 异步流水线流程

### 7.1 导入流水线（upload → READY）

```
用户上传 .bag 文件
     │
     ▼
[ UPLOADING ] 保存文件到 {bag-dir}/{sliceId}/original.bag
     │          创建 DataSlice + IngestionTask 记录
     │          @Async 委派流水线
     ▼
[ PARSING ]   ├─ 更新 task.progress = 30
                ├─ PythonExecutor → parse_rosbag.py
                └─ 解析返回 JSON:
                     ├─ start_ts, end_ts, duration_ms
                     ├─ msg_count_total
                     ├─ gps_bbox { min_lat, min_lon, max_lat, max_lon }
                     └─ streams[] → 每条写入 sensor_stream 表
     │
     ▼
[ TAGGING ]   ├─ 更新 task.progress = 70
                ├─ PythonExecutor → auto_tag.py
                └─ 返回 tag_ids[] → 写入 data_slice_tag 表 (source=auto)
     │
     ▼
[ READY ]     ├─ 更新 DataSlice.status = READY
                ├─ 更新 task.progress = 100, task.status = READY
                └─ 日志记录 dims.slice.created.v1 事件
     │
     ▼
[ ERROR ] ← 任一步骤异常，状态置为 ERROR，记录 error_msg
```

### 7.2 导出流水线（PENDING → READY）

```
用户请求导出 (sliceId, start_ts, end_ts, topics, format)
     │
     ├─ 校验切片存在且 READY
     ├─ 检查相同参数的有效导出（缓存复用）
     └─ 创建 ExportTask (status=PENDING)
     │
     ▼
[ PROCESSING ]  ├─ 更新 export_task.status = PROCESSING
                 ├─ PythonExecutor → extract_export.py
                 │  参数: bag_path, output_path, start_ts, end_ts, topics, format
                 └─ 产物输出到 {export-dir}/{sliceId}/{exportId}/output.bag
     │
     ▼
[ READY ]     ├─ 更新 status=READY, file_path, file_size
                ├─ 重置 expires_at = now + 24h
                └─ 可通过 GET /v1/exports/{exportId} 查询下载链接
     │
     ▼
[ ERROR ]     └─ 更新 status=ERROR

[ EXPIRED ]   ← 超过 created_at + 24h
```

---

## 8. Kafka 事件

### 8.1 事件 Topic

| Topic | 对应事件类 | 状态 | 说明 |
|-------|-----------|------|------|
| `dims.slice.created.v1` | `SliceCreatedEvent` | 仅 log（未真实发送） | 切片导入完成 |
| `dims.slice.deleted.v1` | `SliceDeletedEvent` | 仅 log（未真实发送） | 切片被删除 |

### 8.2 发送机制

代码中存在 `SliceEventPublisher` 组件，但当前流水线中**尚未实际调用 publish 方法**，仅通过 `log.info("Event: dims.slice.created.v1 ...")` 格式记录日志。未来可启用真实 Kafka 发送。

### 8.3 SliceCreatedEvent Payload

```json
{
  "slice_id": "uuid",
  "occurred_at": "2026-07-25T10:00:00Z",
  "name": "slice-name",
  "sensor_types": ["camera", "lidar"],
  "start_ts": "2026-07-25T09:00:00Z",
  "end_ts": "2026-07-25T09:30:00Z",
  "duration_ms": 1800000,
  "gps_bbox": { "min_lat": 31.2, "min_lon": 121.4, "max_lat": 31.3, "max_lon": 121.5 },
  "tags": ["highway"],
  "msg_count_total": 12345,
  "detail_url": "/v1/data-slices/{sliceId}",
  "trace_id": "auto-generated"
}
```

### 8.4 SliceDeletedEvent Payload

```json
{
  "slice_id": "uuid",
  "occurred_at": "2026-07-25T10:00:00Z",
  "reason": "manual",
  "trace_id": "auto-generated"
}
```

---

## 附录: 项目结构

```
dims-server/src/main/java/com/isim/dims/
├── Application.java                    # Spring Boot 入口
├── config/
│   ├── AsyncConfig.java                # 异步线程池配置
│   ├── KafkaConfig.java                # Kafka Producer 配置
│   ├── MyMetaObjectHandler.java        # 自动填充处理器
│   └── StorageConfig.java              # 存储路径配置
├── controller/
│   ├── DataSliceController.java        # 切片 CRUD 端点
│   ├── ExportController.java           # 导出端点
│   ├── TagController.java              # 标签 CRUD 端点
│   └── TaskController.java             # 任务查询 + 健康检查
├── dto/
│   ├── ErrorResponse.java              # 统一错误结构
│   ├── ExportRequest.java              # 导出请求
│   ├── ExportResponse.java             # 导出响应
│   ├── GpsBbox.java                    # GPS 包围盒
│   ├── SliceDetailResponse.java        # 切片详情响应
│   ├── SliceListResponse.java          # 切片列表响应
│   ├── SliceSearchRequest.java         # 多条件检索参数
│   └── TagRequest.java                 # 标签创建/更新请求
├── entity/
│   ├── DataSlice.java                  # 数据切片主表
│   ├── DataSliceTag.java               # 切片-标签关联表
│   ├── ExportTask.java                 # 导出任务表
│   ├── IngestionTask.java              # 导入任务表
│   ├── SensorStream.java               # 传感器流索引表
│   └── Tag.java                        # 标签库表
├── event/
│   ├── SliceCreatedEvent.java          # 切片创建事件 Payload
│   ├── SliceDeletedEvent.java          # 切片删除事件 Payload
│   └── SliceEventPublisher.java        # 事件推送器
├── mapper/
│   ├── DataSliceMapper.java            # 切片 Mapper (含 searchSlices)
│   ├── DataSliceTagMapper.java         # 关联 Mapper (含 selectTagsBySliceId)
│   ├── ExportTaskMapper.java           # 导出任务 Mapper
│   ├── IngestionTaskMapper.java        # 导入任务 Mapper
│   ├── SensorStreamMapper.java         # 传感器流 Mapper
│   └── TagMapper.java                  # 标签 Mapper
├── service/
│   ├── DataSliceService.java           # 切片服务接口
│   ├── ExportPipelineService.java      # 导出流水线接口
│   ├── ExportService.java              # 导出服务接口
│   ├── IngestionPipelineService.java   # 导入流水线接口
│   ├── IngestionService.java           # 导入服务接口
│   ├── TagService.java                 # 标签服务接口
│   └── impl/
│       ├── DataSliceServiceImpl.java
│       ├── ExportPipelineServiceImpl.java
│       ├── ExportServiceImpl.java
│       ├── IngestionPipelineServiceImpl.java
│       ├── IngestionServiceImpl.java
│       └── TagServiceImpl.java
└── util/
    └── PythonExecutor.java             # Python 脚本执行器

dims-server/src/main/resources/
├── application.yml                     # 主配置
├── application-dev.yml                 # 开发环境配置
└── mapper/
    ├── DataSliceMapper.xml             # 多条件检索 SQL
    └── SensorStreamMapper.xml          # 按切片 ID 查询 SQL
```
