package com.example.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalConfigLocationResolverTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty(ExternalConfigLocationResolver.OVERRIDE_DIR_KEY);
    }

    @Test
    void 默认目录名为application() {
        assertThat(ExternalConfigLocationResolver.CONFIG_DIR_NAME).isEqualTo("application");
    }

    @Test
    void 系统属性可覆盖外置配置目录() {
        String custom = Paths.get("D:", "custom-config").toString();
        System.setProperty(ExternalConfigLocationResolver.OVERRIDE_DIR_KEY, custom);

        Path dir = ExternalConfigLocationResolver.resolveConfigDir();

        assertThat(dir).isEqualTo(Paths.get(custom).toAbsolutePath().normalize());
        assertThat(dir.getFileName().toString()).isEqualTo("custom-config");
    }

    @Test
    void 目录location必须以斜杠结尾() {
        Path dir = Paths.get("D:", "work", "demo");

        String location = ExternalConfigLocationResolver.toLocationString(dir);

        assertThat(location).startsWith("file:");
        assertThat(location).endsWith("/");
        assertThat(location).contains("demo");
    }

    @Test
    void 默认解析结果以application结尾() {
        System.clearProperty(ExternalConfigLocationResolver.OVERRIDE_DIR_KEY);

        Path dir = ExternalConfigLocationResolver.resolveConfigDir();

        assertThat(dir.getFileName().toString()).isEqualTo("application");
        assertThat(dir.isAbsolute()).isTrue();
    }
}
