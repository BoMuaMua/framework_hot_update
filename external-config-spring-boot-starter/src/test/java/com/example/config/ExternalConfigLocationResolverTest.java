package com.example.config;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalConfigLocationResolverTest {

    @AfterEach
    void clearOverride() {
        System.clearProperty(ExternalConfigLocationResolver.OVERRIDE_FILE_KEY);
        System.clearProperty(ExternalConfigLocationResolver.OVERRIDE_DIR_KEY);
    }

    @Test
    void 默认文件名为applicationyml() {
        assertThat(ExternalConfigLocationResolver.CONFIG_FILE_NAME).isEqualTo("application.yml");
    }

    @Test
    void 系统属性可覆盖外置配置文件完整路径() {
        String custom = Paths.get("D:", "custom-config", "myapp.yml").toString();
        System.setProperty(ExternalConfigLocationResolver.OVERRIDE_FILE_KEY, custom);

        Path file = ExternalConfigLocationResolver.resolveConfigFile();

        assertThat(file).isEqualTo(Paths.get(custom).toAbsolutePath().normalize());
        assertThat(file.getFileName().toString()).isEqualTo("myapp.yml");
    }

    @Test
    void 系统属性可覆盖外置配置目录并自动拼接文件名() {
        String dir = Paths.get("D:", "custom-config").toString();
        System.setProperty(ExternalConfigLocationResolver.OVERRIDE_DIR_KEY, dir);

        Path file = ExternalConfigLocationResolver.resolveConfigFile();

        assertThat(file.getFileName().toString()).isEqualTo("application.yml");
        assertThat(file.getParent()).isEqualTo(Paths.get(dir).toAbsolutePath().normalize());
    }

    @Test
    void 文件location不能以斜杠结尾() {
        Path file = Paths.get("D:", "work", "demo", "application.yml");

        String location = ExternalConfigLocationResolver.toLocationString(file);

        assertThat(location).startsWith("file:");
        assertThat(location).endsWith("application.yml");
        assertThat(location).doesNotEndWith("/");
    }

    @Test
    void 默认解析结果是绝对路径且以applicationyml结尾() {
        System.clearProperty(ExternalConfigLocationResolver.OVERRIDE_FILE_KEY);
        System.clearProperty(ExternalConfigLocationResolver.OVERRIDE_DIR_KEY);

        Path file = ExternalConfigLocationResolver.resolveConfigFile();

        assertThat(file.getFileName().toString()).isEqualTo("application.yml");
        assertThat(file.isAbsolute()).isTrue();
    }
}
