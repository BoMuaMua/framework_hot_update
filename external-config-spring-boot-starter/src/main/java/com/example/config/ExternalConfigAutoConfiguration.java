package com.example.config;

import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * 引入 external-config-spring-boot-starter 即自动生效：
 * 外置配置目录装配由 spring.factories 里的 EnvironmentPostProcessor 完成，
 * 这里只负责注册热更新相关的 Bean。
 */
@AutoConfiguration
@ConditionalOnClass(ContextRefresher.class)
@ConditionalOnProperty(prefix = "app.hot-reload", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HotReloadProperties.class)
public class ExternalConfigAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExternalConfigAutoConfiguration.class);

    /**
     * BeanPostProcessor 必须由 static 方法创建，否则会导致「is not eligible for getting processed by all
     * BeanPostProcessors」警告并可能漏掉早期 Bean。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "app.hot-reload", name = "refresh-value-fields",
            havingValue = "true", matchIfMissing = true)
    public static ValueFieldRefresher valueFieldRefresher() {
        return new ValueFieldRefresher();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigWatcher configWatcher(HotReloadProperties properties,
                                       ObjectProvider<ContextRefresher> contextRefresher,
                                       ObjectProvider<ValueFieldRefresher> valueFieldRefresher,
                                       ConfigurableEnvironment environment) {
        Path dir = ExternalConfigLocationResolver.resolveConfigDir();
        ContextRefresher refresher = contextRefresher.getIfAvailable();
        if (refresher == null) {
            throw new IllegalStateException("未找到 ContextRefresher，请确认已引入 spring-cloud-context");
        }
        ConfigWatcher watcher = new ConfigWatcher(dir, properties, refresher,
                valueFieldRefresher.getIfAvailable(), environment);
        watcher.start();
        if (watcher.isRunning()) {
            log.info("外置配置热更新组件已注册并启动，监听目录 {}", dir);
        } else {
            log.warn("外置配置热更新组件已注册但未启动，目录不存在或无权限：{}", dir);
        }
        return watcher;
    }
}
