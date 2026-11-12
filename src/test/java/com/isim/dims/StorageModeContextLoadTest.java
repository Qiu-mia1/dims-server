package com.isim.dims;

import com.isim.dims.service.StorageService;
import com.isim.dims.service.impl.LocalStorageService;
import com.isim.dims.service.impl.MinioStorageService;
import com.isim.dims.service.impl.StorageAccess;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 存储模式装配矩阵测试（Review W1 修复验证）
 *
 * <p>local / minio / bos 三种 dims.storage.type 下 context 均须正常启动，
 * 且 StorageService 实现装配正确闭合：
 * <ul>
 *   <li>local → LocalStorageService（唯一实现）</li>
 *   <li>minio → MinioStorageService（唯一实现，LocalStorageService 关闭）</li>
 *   <li>bos → LocalStorageService（bos 恢复链路需要本地文件），BosStorageService 仅下载辅助</li>
 * </ul>
 * minio/bos 的凭证经测试 properties 提供（零密钥入库，不落真实凭据）。
 */
class StorageModeContextLoadTest {

    @Nested
    @SpringBootTest(properties = "dims.storage.type=local")
    class LocalMode {

        @Autowired
        private ApplicationContext context;

        @Test
        void contextLoads_WithLocalStorageService() {
            assertThat(context.getBean(StorageAccess.class)).isNotNull();
            assertThat(context.getBean(StorageService.class))
                    .isInstanceOf(LocalStorageService.class);
            assertThat(context.getBeansOfType(MinioStorageService.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "dims.storage.type=minio",
            "minio.endpoint=http://127.0.0.1:9000",
            "minio.access-key=test",
            "minio.secret-key=test",
            "minio.bucket=dims-test"
    })
    class MinioMode {

        @Autowired
        private ApplicationContext context;

        @Test
        void contextLoads_WithMinioStorageService() {
            assertThat(context.getBean(StorageAccess.class)).isNotNull();
            assertThat(context.getBean(StorageService.class))
                    .isInstanceOf(MinioStorageService.class);
            assertThat(context.getBeansOfType(LocalStorageService.class)).isEmpty();
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "dims.storage.type=bos",
            "bos.endpoint=https://s3.bj.bcebos.com",
            "bos.access-key=test",
            "bos.secret-key=test",
            "bos.bucket=dims-test",
            "bos.region=bj"
    })
    class BosMode {

        @Autowired
        private ApplicationContext context;

        @Test
        void contextLoads_WithLocalStorageServiceForBosRecovery() {
            // W1 修复：bos 模式必须装配 LocalStorageService（BOS 恢复链路需要本地文件）
            assertThat(context.getBean(StorageAccess.class)).isNotNull();
            assertThat(context.getBean(StorageService.class))
                    .isInstanceOf(LocalStorageService.class);
        }
    }
}
