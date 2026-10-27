package com.example.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.cloud.context.refresh.ContextRefresher;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigWatcherTest {

    @TempDir
    Path configDir;

    private ContextRefresher contextRefresher;
    private HotReloadProperties properties;
    private ConfigWatcher watcher;

    @BeforeEach
    void setUp() throws IOException {
        contextRefresher = Mockito.mock(ContextRefresher.class);
        when(contextRefresher.refresh()).thenReturn(java.util.Set.of());

        properties = new HotReloadProperties();
        properties.setDebounceMs(100L);
        properties.setSampleIntervalMs(50L);
        properties.setMaxRetry(10);
        properties.setVerboseLog(false);

        Files.writeString(configDir.resolve("application.yml"), "demo:\n  app-name: 初始值\n",
                StandardCharsets.UTF_8);
        watcher = new ConfigWatcher(configDir, properties, contextRefresher, null);
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.close();
        }
    }

    @Test
    void 启动后无变更不刷新() throws InterruptedException {
        watcher.start();
        Thread.sleep(800L);

        verify(contextRefresher, never()).refresh();
        assertThatStatus(0L, 0L);
    }

    @Test
    void 写入临时文件不触发刷新() throws Exception {
        watcher.start();
        Thread.sleep(300L);

        Files.writeString(configDir.resolve("draft.tmp"), "garbage", StandardCharsets.UTF_8);
        Files.writeString(configDir.resolve("application.yml.swp"), "garbage", StandardCharsets.UTF_8);
        Thread.sleep(1200L);

        verify(contextRefresher, never()).refresh();
        assertThatStatus(0L, 0L);
    }

    @Test
    void 真正修改配置内容才刷新() throws Exception {
        watcher.start();
        Thread.sleep(300L);

        Files.writeString(configDir.resolve("application.yml"), "demo:\n  app-name: 已改变\n",
                StandardCharsets.UTF_8);
        Thread.sleep(2000L);

        verify(contextRefresher, atLeastOnce()).refresh();
    }

    @Test
    void 只改时间戳不改内容不刷新() throws Exception {
        watcher.start();
        Thread.sleep(300L);

        // 只动 mtime，内容保持原样
        Path file = configDir.resolve("application.yml");
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000L));
        Thread.sleep(1500L);

        verify(contextRefresher, never()).refresh();
        var status = watcher.status();
        org.assertj.core.api.Assertions.assertThat(((Number) status.get("skipCount")).longValue()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void 非法YAML刷新失败但监听存活() throws Exception {
        watcher.start();
        Thread.sleep(300L);

        Files.writeString(configDir.resolve("application.yml"), "demo: [unclosed\n", StandardCharsets.UTF_8);
        Mockito.doThrow(new IllegalStateException("模拟解析失败")).when(contextRefresher).refresh();
        Thread.sleep(1500L);

        verify(contextRefresher, atLeastOnce()).refresh();
        assertThatStatus(0L, 1L);
        org.assertj.core.api.Assertions.assertThat(watcher.isRunning()).isTrue();
    }

    private void assertThatStatus(long expectedSuccess, long expectedFail) {
        var status = watcher.status();
        org.assertj.core.api.Assertions.assertThat(((Number) status.get("successCount")).longValue())
                .isEqualTo(expectedSuccess);
        org.assertj.core.api.Assertions.assertThat(((Number) status.get("failCount")).longValue())
                .isEqualTo(expectedFail);
    }
}
