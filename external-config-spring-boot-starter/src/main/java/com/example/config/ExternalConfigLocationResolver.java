package com.example.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

/**
 * 外置配置定位器。
 *
 * <p>约定：外置配置文件 {@code application.yml} 与 jar 包同级（同一个目录），
 * 不参与编译，改完即生效。</p>
 *
 * <p>查找顺序（自上而下，第一个命中的胜出）：</p>
 * <ol>
 *   <li>系统属性 {@code -Dapp.external-config-file=<文件绝对路径>}</li>
 *   <li>系统属性 {@code -Dapp.external-config-dir=<目录>}，自动拼接 {@code application.yml}</li>
 *   <li>jar 所在目录（{@link ApplicationHome}）</li>
 *   <li>当前工作目录 {@code user.dir}</li>
 *   <li>{@code user.dir} 的一级子目录（IDE 多模块运行：项目根目录下的 app/application.yml）</li>
 * </ol>
 *
 * <p>本类必须是 {@link EnvironmentPostProcessor}，因为它要写的
 * {@code spring.config.additional-location} 必须在配置文件被读取之前生效。</p>
 */
public class ExternalConfigLocationResolver implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ExternalConfigLocationResolver.class);

    /** 外置配置文件名，与 jar 同级 */
    public static final String CONFIG_FILE_NAME = "application.yml";

    /** Spring Boot 的额外配置位置键 */
    public static final String ADDITIONAL_LOCATION_KEY = "spring.config.additional-location";

    /** 自定义外置配置文件完整路径的系统属性键（优先级最高） */
    public static final String OVERRIDE_FILE_KEY = "app.external-config-file";

    /** 自定义外置配置文件所在目录的系统属性键（会自动拼接 application.yml） */
    public static final String OVERRIDE_DIR_KEY = "app.external-config-dir";

    private static final String PROPERTY_SOURCE_NAME = "externalConfigLocation";

    /** 只在第一次装配时打印 INFO/WARN，避免重复刷新时刷屏 */
    private static final AtomicBoolean FIRST_APPLY = new AtomicBoolean(true);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path file = resolveConfigFile();
        Path parent = file.getParent();

        if (parent == null || !Files.isDirectory(parent)) {
            if (FIRST_APPLY.getAndSet(false)) {
                log.warn("未发现外置配置文件所在目录 [{}]，本次使用 jar 内默认配置，热更新不会生效。"
                        + "可用 -D{}=<绝对路径> 显式指定", file, OVERRIDE_FILE_KEY);
            }
            return;
        }

        String existing = environment.getProperty(ADDITIONAL_LOCATION_KEY);
        if (StringUtils.hasText(existing)) {
            if (FIRST_APPLY.getAndSet(false)) {
                log.info("已显式配置 {} = {}，跳过自动装配", ADDITIONAL_LOCATION_KEY, existing);
            }
            return;
        }

        String location = "optional:" + toLocationString(file);
        Map<String, Object> source = new HashMap<>(2);
        source.put(ADDITIONAL_LOCATION_KEY, location);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, source));

        if (FIRST_APPLY.getAndSet(false)) {
            if (Files.isRegularFile(file)) {
                log.info("外置配置文件已装配：{} -> {}", file, location);
            } else {
                log.info("外置配置文件暂不存在，已预留加载位置：{}（文件出现后自动加载并热更新）", file);
            }
        } else {
            log.debug("外置配置文件已重新装配：{}", location);
        }
    }

    @Override
    public int getOrder() {
        // 必须早于 ConfigDataEnvironmentPostProcessor（其 order 为 HIGHEST_PRECEDENCE + 10）
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /**
     * 解析外置配置文件的绝对路径（文件可能尚不存在）。
     */
    public static Path resolveConfigFile() {
        String fileOverride = System.getProperty(OVERRIDE_FILE_KEY);
        if (StringUtils.hasText(fileOverride)) {
            return normalize(Paths.get(fileOverride));
        }
        String dirOverride = System.getProperty(OVERRIDE_DIR_KEY);
        if (StringUtils.hasText(dirOverride)) {
            return normalize(Paths.get(dirOverride)).resolve(CONFIG_FILE_NAME).toAbsolutePath().normalize();
        }
        return resolveBaseDir().resolve(CONFIG_FILE_NAME).toAbsolutePath().normalize();
    }

    /**
     * 解析「application.yml 应该放在哪个目录」。
     */
    public static Path resolveBaseDir() {
        Path userDir = normalize(Paths.get(System.getProperty("user.dir", ".")));
        Path fromHome = null;

        try {
            // 绝不能传本类：那样会锁死在 starter 模块的 target/classes，与应用实际运行目录无关
            ApplicationHome home = new ApplicationHome();
            if (home.getDir() != null) {
                fromHome = normalize(home.getDir().toPath());
                if (Files.isRegularFile(fromHome.resolve(CONFIG_FILE_NAME))) {
                    return fromHome;
                }
            }
        } catch (RuntimeException ex) {
            log.debug("ApplicationHome 解析失败，尝试 user.dir", ex);
        }

        if (Files.isRegularFile(userDir.resolve(CONFIG_FILE_NAME))) {
            return userDir;
        }

        Path inSubModule = findInSubModules(userDir);
        if (inSubModule != null) {
            return inSubModule;
        }

        return fromHome != null ? fromHome : userDir;
    }

    /**
     * IDE 多模块运行场景：user.dir 是项目根，application.yml 在 app/ 子模块下。
     */
    private static Path findInSubModules(Path userDir) {
        if (!Files.isDirectory(userDir)) {
            return null;
        }
        try (var stream = Files.list(userDir)) {
            return stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().equals("target"))
                    .filter(p -> Files.isRegularFile(p.resolve(CONFIG_FILE_NAME)))
                    .findFirst()
                    .orElse(null);
        } catch (IOException ex) {
            log.debug("扫描子模块失败：{}", userDir, ex);
            return null;
        }
    }

    /**
     * 把配置文件路径转成 Spring Boot 认得的 location 字符串。
     *
     * <p>注意：目录必须以 {@code /} 结尾，而<b>文件不能</b>带结尾斜杠，
     * 否则 Spring Boot 会把它当成目录。</p>
     */
    public static String toLocationString(Path file) {
        return file.toUri().toString();
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
