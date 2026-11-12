package com.isim.dims.service.impl;

import com.isim.dims.config.CameraEventConfig;
import com.isim.dims.dto.FilterOption;
import com.isim.dims.util.MxfSpecialEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 触发类型选项提供者 —— 「筛选器 → 事件名（含中文标题）」的唯一组装点。
 *
 * <p>数据源是 {@link CameraEventConfig#getFilterEventIndex()}（进程启动时从
 * {@code classpath:config/camera_event_config.json} 载入并建立反查索引），
 * 纯内存只读，<b>不查库</b>。</p>
 *
 * <h3>为什么单独抽出来</h3>
 * <p>智驾事件看板的「筛选器」下拉（{@code MxfVehicleDashboardServiceImpl#buildFilterOptions}）
 * 与数据浏览器筛选栏的「触发类型」下拉，展示的必须是同一批筛选器 / 事件。此前前者在
 * Service 内部私有实现，若后者另写一份，配置一改就会出现两处不一致。两处都依赖本类
 * （以及共同的 {@link MxfSpecialEvents#titleOf(String)}），口径由构造方式保证一致。</p>
 *
 * <p>⚠️ 与看板那处唯一的差别是<b>筛选器的来历</b>：看板支持传入 {@code event} 参数回填其所属
 * 筛选器，走的是同一个 {@code filterEventIndex}，所以选项列表本身完全相同。</p>
 *
 * @feature: mxf, slice
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FilterOptionProvider {

    private final CameraEventConfig cameraEventConfig;

    /**
     * 配置里有、但不作为「触发类型」检索项展示的筛选器。
     *
     * <p>{@code obf_*} 是内部埋点事件：列表查询已把它们排除（{@code event_name not in ('obf_issue','bsw_log')}），
     * 下拉里选它恒为空结果。但事件条目必须留在配置里 —— 那里的 {@code cameras: []} 是
     * 「不转换任何摄像头」的语义，删掉会让未知事件走 fail-open 变成全量转换。</p>
     */
    private static final Set<String> HIDDEN_FILTERS = Set.of("obf_issue_filter");

    /**
     * 该筛选器是否不作为检索项展示（见 {@link #HIDDEN_FILTERS} 的原因说明）。
     *
     * <p>智驾看板中间列 {@code MxfVehicleDashboardServiceImpl#buildFilterOptions} 与
     * {@link #list()} 是同一份口径，两处都要过这个方法 —— 否则会出现「数据浏览器剔了、看板还留着」。</p>
     */
    public static boolean isHiddenFilter(String filter) {
        return filter != null && HIDDEN_FILTERS.contains(filter);
    }

    /**
     * 全部筛选器选项，顺序即 {@code camera_event_config.json} 中 events 的出现顺序
     * （一个筛选器只出现一次，即使它映射多个事件）。{@link #HIDDEN_FILTERS} 内的筛选器不展示。
     *
     * @return 不可变语义的选项列表；配置缺失 / 加载失败时返回空列表（调用方据此显示空态）
     */
    public List<FilterOption> list() {
        Map<String, List<String>> index = cameraEventConfig.getFilterEventIndex();
        if (index == null || index.isEmpty()) {
            log.debug("[FilterOption] 筛选器配置为空（camera_event_config.json 未加载或 events 为空）");
            return List.of();
        }
        List<FilterOption> options = new ArrayList<>(index.size());
        index.forEach((filter, names) -> {
            if (isHiddenFilter(filter)) {
                return;
            }
            List<String> eventNames = names == null ? List.of() : List.copyOf(names);
            options.add(FilterOption.builder()
                    .filter(filter)
                    .eventNames(eventNames)
                    .titles(eventNames.stream().map(MxfSpecialEvents::titleOf).toList())
                    .build());
        });
        return options;
    }

    /**
     * 筛选器名 → 该筛选器命中的事件名（保持配置顺序）。
     *
     * <p>检索侧用它把「触发类型」下拉选中的筛选器展开成要查的事件名 —— 与
     * {@link #list()} 同一份配置、同一个 {@code resolveEventsByFilter} 口径，
     * 保证「下拉里能选到的」和「后端能查到的」永远对得上。</p>
     *
     * <p>边界：未命中配置时返回单元素列表 {@code [filter]} —— 当作事件名去走原有的模糊匹配，
     * 结果是空集而不是全量（手工拼 URL 传了错值时不会静默返回全部数据）。</p>
     *
     * @param filter 筛选器名（{@code events.*.filter}）
     * @return 事件名列表；{@code filter} 为空 → 空列表
     */
    public List<String> resolveEventNames(String filter) {
        if (filter == null || filter.isBlank()) {
            return List.of();
        }
        List<String> events = cameraEventConfig.resolveEventsByFilter(filter);
        return events.isEmpty() ? List.of(filter.trim()) : events;
    }
}
