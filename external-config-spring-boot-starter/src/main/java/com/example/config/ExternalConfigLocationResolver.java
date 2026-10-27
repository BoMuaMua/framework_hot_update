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
 * 把「jar 同级目录下的 application/」装配为外置配置源。
 *
 * <p>执行时机：EnvironmentPostProcessor，顺序 {@link Ordered#HIGHEST_PRECEDENCE}，
 * 早于 Spring Boot 的 ConfigDataEnvironmentPostProcessor（HIGHEST_PRECEDENCE + 10），
 * 因此这里写入的 spring.config.additional-location 会被配置数据加载流程读到。
 *
 * <p>注意：每次 {@code ContextRefresher.refresh()} 都会重新跑一遍 EnvironmentPostProcessor，
 * 所以首次装配打 INFO，后续打 DEBUG，避免日志刷屏。
 *
 * <p>优先级：用户显式指定 -Dapp.external-config-dir 或 spring.config.additional-location 时，本类让路。
 */
public class ExternalConfigLocationResolver implements EnvironmentPostProcessor, Ordered {

    private static final Logger log = LoggerFactory.getLogger(ExternalConfigLocationResolver.class);

    /** 外置配置目录名，与 jar 同级 */
    public static final String CONFIG_DIR_NAME = "application";

    /** Spring Boot 的额外配置位置键 */
    public static final String ADDITIONAL_LOCATION_KEY = "spring.config.additional-location";

    /** 自定义外置目录的系统属性键（可选覆盖） */
    public static final String OVERRIDE_DIR_KEY = "app.external-config-dir";

    private static final String PROPERTY_SOURCE_NAME = "externalConfigLocation";

    /** refresh 会重复触发本类，首次装配才打 INFO */
    private static final AtomicBoolean FIRST_APPLY = new AtomicBoolean(true);

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Path dir = resolveConfigDir();
        if (!Files.isDirectory(dir)) {
            if (FIRST_APPLY.getAndSet(false)) {
                log.warn("未发现外置配置目录 [{}]，本次使用 jar 内默认配置，热更新不会生效。"
                        + "可用 -D{}=<绝对路径> 显式指定", dir, OVERRIDE_DIR_KEY);
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

        String location = "optional:" + toLocationString(dir);
        Map<String, Object> source = new HashMap<>(2);
        source.put(ADDITIONAL_LOCATION_KEY, location);
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, source));
        if (FIRST_APPLY.getAndSet(false)) {
            log.info("外置配置目录已装配：{} -> {}", dir, location);
        } else {
            log.debug("外置配置目录已重新装配：{}", location);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    /** 解析外置配置目录：-Dapp.external-config-dir 优先，否则 jar 同级 / IDE 下的工作目录 */
    public static Path resolveConfigDir() {
        String override = System.getProperty(OVERRIDE_DIR_KEY);
        if (StringUtils.hasText(override)) {
            return Paths.get(override).toAbsolutePath().normalize();
        }
        return resolveBaseDir().resolve(CONFIG_DIR_NAME).toAbsolutePath().normalize();
    }

    /**
     * 解析基准目录。
     *
     * <p>按候选顺序找，返回第一个「下面确实存在 application/ 目录」的基准：
     * <ol>
     *   <li>无参 {@link ApplicationHome}：它通过调用栈定位<b>启动类</b>所在位置，
     *       fat jar 运行时即 jar 所在目录，IDE 运行时是应用模块的 target/classes；</li>
     *   <li>{@code user.dir}：IDE 里通常就是应用模块根目录，也是 Maven 的 workingDirectory；</li>
     *   <li>兜底返回 user.dir（目录不存在时由调用方决定是否装配）。</li>
     * </ol>
     *
     * <p>注意：绝不能把本类（starter 里的类）传给 ApplicationHome——那会把位置锁死在
     * starter 模块自己的 target/classes 上，与应用实际运行目录无关。
     */
    public static Path resolveBaseDir() {
        Path userDir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();

        Path fromHome = null;
        try {
            ApplicationHome home = new ApplicationHome();
            if (home.getDir() != null) {
                fromHome = home.getDir().toPath().toAbsolutePath().normalize();
                if (Files.isDirectory(fromHome.resolve(CONFIG_DIR_NAME))) {
                    return fromHome;
                }
            }
        } catch (RuntimeException ex) {
            log.debug("ApplicationHome 解析失败，尝试 user.dir", ex);
        }

        if (Files.isDirectory(userDir.resolve(CONFIG_DIR_NAME))) {
            return userDir;
        }

        // 多模块 IDE 运行：user.dir 往往是项目根，application/ 在某个子模块下
        Path inSubModule = findInSubModules(userDir);
        if (inSubModule != null) {
            return inSubModule;
        }

        // 都没找到时优先返回启动类目录，便于日志里显示最可能的位置
        return fromHome != null ? fromHome : userDir;
    }

    /** 在 user.dir 的直接子目录里找第一个包含 application/ 的目录（多模块工程用） */
    private static Path findInSubModules(Path userDir) {
        if (!Files.isDirectory(userDir)) {
            return null;
        }
        try (var stream = Files.list(userDir)) {
            return stream.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().equals("target"))
                    .filter(p -> Files.isDirectory(p.resolve(CONFIG_DIR_NAME)))
                    .findFirst()
                    .orElse(null);
        } catch (IOException ex) {
            log.debug("扫描子模块失败：{}", userDir, ex);
            return null;
        }
    }

    /** 目录转 Spring Boot 可识别的 location 字符串（URI 形式，必须以 / 结尾才表示目录） */
    public static String toLocationString(Path dir) {
        String uri = dir.toUri().toString();
        return uri.endsWith("/") ? uri : uri + "/";
    }
}
