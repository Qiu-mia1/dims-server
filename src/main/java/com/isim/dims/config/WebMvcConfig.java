package com.isim.dims.config;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Web MVC 配置
 *
 * <p>配置静态资源映射，使 /data/videos/** 路径下的视频文件可通过 HTTP 访问。
 * 同时配置缓存策略以减少重复请求。</p>
 *
 * <h3>异步流式线程池</h3>
 * <p>VideoStreamingController 使用 StreamingResponseBody 异步流式服务视频。</p>
 * <p>默认情况下，Spring Boot 使用 auto-configured ThreadPoolTaskExecutor 处理 MVC 异步请求，
 * 其 queueCapacity=Integer.MAX_VALUE，导致线程数被 corePoolSize=8 限制，无法扩容。
 * 12 路视频并发时，仅 8 路可同时播放，其余 4 路在队列中等待。</p>
 * <p>本配置显式设置 MVC 异步线程池，专用于视频流式传输：</p>
 * <ul>
 *   <li>corePoolSize=8：基线常驻线程，覆盖大部分场景</li>
 *   <li>maxPoolSize=24：12 路全量 + 额外余量，应对用户并发</li>
 *   <li>queueCapacity=48：排队等待的连接数（视频加载初始请求突发）</li>
 *   <li>CallerRunsPolicy：队列满时回退到 Tomcat 工作线程（削峰）</li>
 * </ul>
 *
 * <p>注意：此线程池仅用于 MVC 异步请求（StreamingResponseBody），
 * 不影响 {@link org.springframework.scheduling.annotation.Async} 注解的线程池
 * （由 {@link AsyncConfig} 管理，core=2/max=4 用于导入导出流水线）。</p>
 * @feature: infra
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 静态资源映射 /data/videos/**（本地文件系统直出）
     *
     * <p>MinIO 迁移：视频文件直出仅存在于 local 模式（minio 模式下
     * 全部经 VideoStreamingController → StorageAccess 代理读取，含归档拦截）。
     * 配置项 dims.storage.type=local（默认）时装配，minio 模式整体停用。</p>
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "dims.storage.type", havingValue = "local", matchIfMissing = true)
    static class LocalVideoResourceConfig implements WebMvcConfigurer {

        @Autowired(required = false)
        private StorageConfig storageConfig;

        @Override
        public void addResourceHandlers(ResourceHandlerRegistry registry) {
            if (storageConfig != null) {
                registry.addResourceHandler("/data/videos/**")
                        .addResourceLocations("file:" + storageConfig.getVideoDir() + "/")
                        .setCacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                        .resourceChain(true);
            }
        }
    }

    /**
     * 配置 MVC 异步请求线程池（专用于视频流式传输）
     *
     * <p>覆盖 Spring Boot 默认的 SimpleAsyncTaskExecutor 或 auto-configured
     * unbounded-queue ThreadPoolTaskExecutor，使用有界线程池处理视频流。</p>
     *
     * <table>
     *   <tr><th>参数</th><th>值</th><th>理由</th></tr>
     *   <tr><td>corePoolSize</td><td>8</td><td>覆盖基线并发：8 路同时播放</td></tr>
     *   <tr><td>maxPoolSize</td><td>24</td><td>弹性扩容：12 路 + 1 个用户总量</td></tr>
     *   <tr><td>queueCapacity</td><td>48</td><td>削峰填谷：应对 2 个用户瞬时请求</td></tr>
     *   <tr><td>CallerRunsPolicy</td><td>√</td><td>队列满时 Tomcat worker 直写，不丢失请求</td></tr>
     * </table>
     */
    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(24);
        executor.setQueueCapacity(48);
        executor.setThreadNamePrefix("dims-video-stream-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        configurer.setTaskExecutor(executor);

        // 异步请求超时：30 分钟（长视频流），覆盖 application.yml 的 10 分钟默认值
        configurer.setDefaultTimeout(TimeUnit.MINUTES.toMillis(30));
    }
}
