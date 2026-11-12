package com.isim.dims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DIMS — 数据接入与管理子系统
 * Data Ingestion & Management Subsystem
 *
 * <p>{@code @EnableFeignClients}：注册 IAMS 用户信息查询 Client
 * （{@link com.isim.dims.client.IamsUserClient}）。客户端懒加载，
 * 未注入使用前不产生任何服务发现/网络调用。</p>
 *
 * @feature: infra
 */
@SpringBootApplication
@EnableFeignClients
@EnableScheduling
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
