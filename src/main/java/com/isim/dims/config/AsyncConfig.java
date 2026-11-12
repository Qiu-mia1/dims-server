package com.isim.dims.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置
 *
 * <p>后端异步任务主要为导入/导出流水线（Python 脚本调用 + DB 写入），
 * 非高并发场景，线程池偏小。</p>
 *
 * <table>
 *   <tr><th>参数</th><th>值</th><th>理由</th></tr>
 *   <tr><td>corePoolSize</td><td>2</td><td>基线常驻线程，覆盖大部分场景</td></tr>
 *   <tr><td>maxPoolSize</td><td>4</td><td>突发任务高峰时扩容上限</td></tr>
 *   <tr><td>queueCapacity</td><td>100</td><td>排队等待执行的任务</td></tr>
 *   <tr><td>CallerRunsPolicy</td><td>√</td><td>队列满时由调用线程执行，削峰不丢失</td></tr>
 * </table>
 *
 * <p><b>上表是早期设计值，与实际实现不一致</b>：当前各线程池多数连续调用两次
 * {@code setCorePoolSize(32)}、且未调用 {@code setMaxPoolSize}（只有 vinBackfillExecutor
 * 保留了 core=2/max=4）。调整并发时请以方法体内的实际代码为准，并同步修正本注释。</p>
 *
 * <p>线程池用途对照：</p>
 * <ul>
 *   <li>getAsyncExecutor（{@code dims-async-}）：@Async 默认池，导入/导出流水线</li>
 *   <li>videoExtractExecutor：ffmpeg 视频转码（CPU/内存密集，注释原设计串行）</li>
 *   <li>sceneExtractExecutor：场景提取 Python 管道</li>
 *   <li>cockpitExtractExecutor：驾驶舱统计提取</li>
 *   <li>localImportExecutor：本地目录导入长流水线</li>
 *   <li>vinBackfillExecutor：VIN-MD5 导入后的批量回写</li>
 * </ul>
 *
 * @feature: infra
 */
@Configuration
@EnableAsync(proxyTargetClass = true)
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(32);                 // 常驻 32 个线程
        executor.setCorePoolSize(32);                   // 最大也是 20，配合有界队列行为可预测
        executor.setQueueCapacity(64);               // 超出 20 个的任务排队
        executor.setKeepAliveSeconds(30);            // 空闲 30 秒后回收
        executor.setAllowCoreThreadTimeOut(true);    // 关键：允许核心线程也被回收
        executor.setThreadNamePrefix("dims-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 视频段提取串行转码线程池
     *
     * <p>ffmpeg HEVC→H.264 为 CPU + 内存密集任务，AnyReader 打开 bag 文件 +
     * ffmpeg 转码并发 3 路时峰值内存可达 3-6GB，触发 OOM Killer。
     * 改为单线程串行执行，牺牲速度换稳定性。队列容量 64 足以容纳
     * 单切片全部 topic 的提交。</p>
     *
     * <p>共享于所有切片的视频提取任务：全局串行执行。</p>
     */
    @Bean(name = "videoExtractExecutor")
    public ThreadPoolTaskExecutor videoExtractExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(32);                 // 常驻 32 个线程
        executor.setCorePoolSize(32);                   // 最大也是 6，配合有界队列行为可预测
        executor.setQueueCapacity(64);               // 超出 6 个的任务排队
        executor.setKeepAliveSeconds(30);            // 空闲 30 秒后回收
        executor.setAllowCoreThreadTimeOut(true);    // 关键：允许核心线程也被回收
        executor.setThreadNamePrefix("dims-video-extract-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    /**
     * 场景提取流水线线程池
     *
     * <p>场景提取为串行 Python 管道（GPS → 感知 → 统一格式 → 规则引擎），
     * 任务重、耗时久（单任务可达 10 分钟），并发度刻意压小：
     * 核心 1 + 队列 10 保证常态下逐切片顺序执行，突发时最多 2 路并行。
     * CallerRunsPolicy 在队列满时由调用线程兜底执行，削峰不丢失。</p>
     */
    @Bean(name = "sceneExtractExecutor")
    public ThreadPoolTaskExecutor sceneExtractExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(32);                 // 常驻 32 个线程
        executor.setCorePoolSize(32);                  // 最大也是 20，配合有界队列行为可预测
        executor.setQueueCapacity(64);               // 超出 20 个的任务排队
        executor.setKeepAliveSeconds(30);            // 空闲 30 秒后回收
        executor.setAllowCoreThreadTimeOut(true);    // 关键：允许核心线程也被回收
        executor.setThreadNamePrefix("dims-scene-extract-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    /**
     * 驾驶舱统计提取流水线线程池
     *
     * <p>统计提取为单 Python 脚本（单遍扫描 ~30s），与场景提取独立并行：
     * 核心 1 + 队列 10 保证按 slice 顺序执行，突发时最多 2 路并行。
     * CallerRunsPolicy 在队列满时由调用线程兜底执行，削峰不丢失。</p>
     */
    @Bean(name = "cockpitExtractExecutor")
    public ThreadPoolTaskExecutor cockpitExtractExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(32);                 // 常驻 32 个线程
        executor.setMaxPoolSize(32);                  // 最大也是 20，配合有界队列行为可预测
        executor.setQueueCapacity(64);               // 超出 20 个的任务排队
        executor.setKeepAliveSeconds(30);            // 空闲 30 秒后回收
        executor.setAllowCoreThreadTimeOut(true);    // 关键：允许核心线程也被回收
        executor.setThreadNamePrefix("dims-cockpit-extract-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    /**
     * 本地目录导入流水线线程池
     *
     * <p>LOCAL 导入为长耗时串行流水线（复制 + Docker 转换 + 解析，单会话可达数分钟），
     * 固定单线程独立执行，避免挤占共享 dims-async 池（上传/导出任务）。
     * CallerRunsPolicy 在队列满时由调用线程兜底执行，削峰不丢失。</p>
     */
    @Bean("localImportExecutor")
    public ThreadPoolTaskExecutor localImportExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(32);                 // 常驻 32 个线程
        executor.setCorePoolSize(32);                   // 最大也是 20，配合有界队列行为可预测
        executor.setQueueCapacity(64);               // 超出 20 个的任务排队
        executor.setKeepAliveSeconds(30);            // 空闲 30 秒后回收
        executor.setAllowCoreThreadTimeOut(true);    // 关键：允许核心线程也被回收
        executor.setThreadNamePrefix("dims-local-import-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    /**
     * VIN 回写线程池
     *
     * <p>VIN-MD5 映射导入后按 import_md5 批量回写 data_slice 的 vin / vin_md5_id，
     * 是「一条 SQL 打完」的轻量任务，但仍可能因历史切片量大而跑上数秒，
     * 故独立成池，不占用共享 dims-async 池（上传/导出任务）。</p>
     */
    @Bean("vinBackfillExecutor")
    public ThreadPoolTaskExecutor vinBackfillExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);                  // 常态基本无任务
        executor.setMaxPoolSize(4);                   // 连续导入时最多 4 路并行
        executor.setQueueCapacity(64);
        executor.setKeepAliveSeconds(30);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("dims-vin-backfill-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return (ex, method, params) -> {
            Logger log = LoggerFactory.getLogger(method.getDeclaringClass());
            log.error("Async method [{}#{}] threw exception: {}",
                    method.getDeclaringClass().getSimpleName(),
                    method.getName(),
                    ex.getMessage(),
                    ex);
        };
    }
}
