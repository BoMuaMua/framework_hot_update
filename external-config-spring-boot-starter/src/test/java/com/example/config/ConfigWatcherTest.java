package com.example.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.context.refresh.ContextRefresher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfigWatcherTest {

    @TempDir
    Path tempDir;

    private Path configFile;
    private HotReloadProperties properties;
    private ContextRefresher contextRefresher;
    private ConfigWatcher watcher;

    @BeforeEach
    void setUp() throws IOException {
        configFile = tempDir.resolve(ExternalConfigLocationResolver.CONFIG_FILE_NAME);
        Files.writeString(configFile, "demo:\n  app-name: 初始值\n", StandardCharsets.UTF_8);

        properties = new HotReloadProperties();
        properties.setDebounceMs(100L);
        properties.setSampleIntervalMs(50L);
        properties.setMaxRetry(10);
        properties.setVerboseLog(false);

        contextRefresher = mock(ContextRefresher.class);
        when(contextRefresher.refresh()).thenReturn(java.util.Set.of());

        watcher = new ConfigWatcher(configFile, properties, contextRefresher, null);
        watcher.start();
    }

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.close();
        }
    }

    @Test
    void 启动后无变更不刷新() throws Exception {
        Thread.sleep(800);

        verify(contextRefresher, never()).refresh();
        assertThat(watcher.isRunning()).isTrue();
        assertThat(watcher.status().get("successCount")).isEqualTo(0L);
        assertThat(watcher.status().get("failCount")).isEqualTo(0L);
    }

    @Test
    void 同目录下的其他文件不触发刷新() throws Exception {
        Files.writeString(tempDir.resolve("draft.tmp"), "x", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("application.yml.swp"), "x", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve("other.yml"), "x", StandardCharsets.UTF_8);
        Thread.sleep(1200);

        verify(contextRefresher, never()).refresh();
    }

    @Test
    void 真正修改配置内容才刷新() throws Exception {
        Files.writeString(configFile, "demo:\n  app-name: 改过了\n", StandardCharsets.UTF_8);
        Thread.sleep(2000);

        verify(contextRefresher, atLeastOnce()).refresh();
        assertThat(watcher.status().get("successCount")).isEqualTo(1L);
        assertThat(watcher.status().get("failCount")).isEqualTo(0L);
    }

    @Test
    void 只改时间戳不改内容不刷新() throws Exception {
        Files.setLastModifiedTime(configFile,
                FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Thread.sleep(1500);

        verify(contextRefresher, never()).refresh();
        assertThat((Long) watcher.status().get("skipCount")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    void 非法YAML刷新失败但监听存活() throws Exception {
        doThrow(new IllegalStateException("模拟解析失败")).when(contextRefresher).refresh();

        Files.writeString(configFile, "demo: [unclosed\n", StandardCharsets.UTF_8);
        Thread.sleep(1500);

        verify(contextRefresher, atLeastOnce()).refresh();
        assertThat(watcher.status().get("successCount")).isEqualTo(0L);
        assertThat(watcher.status().get("failCount")).isEqualTo(1L);
        assertThat(watcher.isRunning()).isTrue();
    }
}
