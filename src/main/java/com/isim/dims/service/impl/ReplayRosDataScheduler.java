package com.isim.dims.service.impl;

import com.isim.dims.entity.DataSlice;
import com.isim.dims.mapper.DataSliceMapper;
import com.isim.dims.service.ReplayRosDataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 回放 rosdata.json 定时重跑调度器。
 *
 * <p>从 {@link ReplayRosDataService} 抽取产物在导入之后偶发不可读（如 minio 上传失败但
 * 导入已成功提交），本调度器定期扫描候选切片并重新生成 rosdata.json。失败时仅写
 * {@code data_slice.error_msg}（成功时清空），不引入新字段。</p>
 *
 * <h3>扫描策略（不依赖新字段）</h3>
 * <ul>
 *   <li>筛选 {@code status IN ('READY','WORN')}（已成功导入、bag 仍可访问）；</li>
 *   <li>按 {@code updated_at} 升序遍历（NULLS FIRST），{@code updated_at} 为空的
 *       （从未被更新过）优先处理；</li>
 *   <li>单批 {@code dims.replay.reextract.batch-size}（默认 20），分批 sleep 走完避免堆内存峰值；</li>
 *   <li>冷却：跳过 {@code updated_at} 在 {@code dims.replay.reextract.cooldown-minutes}
 *       分钟内（即最近刚被本任务或导入链路改过）的切片 —— 重跑成功 / 失败都会刷新
 *       {@code updated_at}（MP {@code FieldFill=INSERT_UPDATE} 自动），天然实现
 *       「最近跑过就不重复跑」；{@code updated_at IS NULL} 的不受冷却限制，始终入选。</li>
 *   <li>每条间隔 sleep {@code dims.replay.reextract.interval-ms}（默认 0，可压平突发）。</li>
 * </ul>
 *
 * <h3>防重入与开关</h3>
 * <ul>
 *   <li>{@link AtomicBoolean} {@code running} 保证上一次 tick 未结束不并发执行；</li>
 *   <li>{@code dims.replay.reextract.enabled=false} 时整 Bean 不装配（默认 true）。</li>
 * </ul>
 *
 * <p>注意：本 Bean 装配需在应用入口类上启用 {@code @EnableScheduling}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dims.replay.reextract.enabled", havingValue = "true", matchIfMissing = true)
public class ReplayRosDataScheduler {

    private final DataSliceMapper dataSliceMapper;
    private final ReplayRosDataService replayRosDataService;

    @Value("${dims.replay.reextract.batch-size:20}")
    private int batchSize;

    @Value("${dims.replay.reextract.cooldown-minutes:5}")
    private int cooldownMinutes;

    @Value("${dims.replay.reextract.interval-ms:0}")
    private long intervalMs;

    /**
     * 防重入：上一次 tick 未结束则跳过本次
     */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * 定时入口。cron 默认每小时整点；可通过 {@code DIMS_REPLAY_REEXTRACT_CRON} 覆盖。
     * initialDelay 让应用冷启动 + 导入收尾后再跑第一轮，避免抢资源。
     */
//    @Scheduled(cron = "0 0/1 * * * ? ")
    public void reextractTick() {
        if (!running.compareAndSet(false, true)) {
            log.info("[replay] scheduler 上一轮尚未结束，跳过本次 tick");
            return;
        }
        try {
            doReextract();
        } catch (Exception e) {
            log.error("[replay] scheduler 顶层异常（已吞掉）: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 实际遍历：取 status='READY' 中 updated_at 最早的 batchSize 条，依次调
     * {@link ReplayRosDataService#reExtractBySliceId(String)}。
     */
    private void doReextract() {
        // 全部用 QueryWrapper + 字符串列名，避免 lambda 静态方法引用触发 IDE 缓存误判
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DataSlice> qw =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        qw.in("status", "READY", "WORN")
                // 冷却：用现有 updated_at 字段过滤最近改过的切片（数值来自 application.yml，安全）。
                // updated_at IS NULL 的行（从未被更新过）必须包含 —— SQL 中 NULL < x 不成立，
                // 不加 OR 会被该条件整体排除。
                .apply(cooldownMinutes > 0,
                        "(updated_at IS NULL OR updated_at < (CURRENT_TIMESTAMP AT TIME ZONE 'UTC')"
                                + " - INTERVAL '" + cooldownMinutes + " minutes')")
                // NULLS FIRST：从未更新过的（updated_at 为空）优先重跑
                .last("ORDER BY updated_at ASC NULLS FIRST LIMIT " + Math.max(1, batchSize));

        List<DataSlice> candidates = dataSliceMapper.selectList(qw);
        if (candidates.isEmpty()) {
            log.info("[replay] scheduler 本轮无候选切片（status=READY 或 WORN 且已就绪）");
            return;
        }
        log.info("[replay] scheduler 本轮候选 {} 条，batchSize={}", candidates.size(), batchSize);

        int idx = 0;
        for (DataSlice s : candidates) {
            idx++;
            try {
                replayRosDataService.reExtractBySliceId(s.getId());
            } catch (Exception e) {
                // service 内部已捕获并写 error_msg，这里仅记 warn
                log.warn("[replay] scheduler sliceId={} 处理异常（service 已记账）: {}",
                        s.getId(), e.getMessage());
            }
            if (intervalMs > 0 && idx < candidates.size()) {
                try {
                    Thread.sleep(intervalMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("[replay] scheduler 间隔 sleep 被中断，提前结束本轮");
                    break;
                }
            }
        }
    }
}