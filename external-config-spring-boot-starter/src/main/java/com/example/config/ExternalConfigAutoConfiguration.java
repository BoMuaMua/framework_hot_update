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
 * 外置配置热更新自动装配。
 *
 * <p>引入本 starter 后无需任何注解或配置即生效：
 * 所有属性都有默认值，{@code matchIfMissing = true} 表示不写就是开启。</p>
 */
@AutoConfiguration
@ConditionalOnClass(ContextRefresher.class)
@ConditionalOnProperty(prefix = "app.hot-reload", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(HotReloadProperties.class)
public class ExternalConfigAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ExternalConfigAutoConfiguration.class);

    /**
     * 必须声明为 static：BeanPostProcessor 的工厂方法若为非 static，
     * 会导致配置类被迫提前实例化，触发「is not eligible for getting processed by all
     * BeanPostProcessors」告警。
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "app.hot-reload", name = "refresh-value-fields", havingValue = "true", matchIfMissing = true)
    public static ValueFieldRefresher valueFieldRefresher() {
        return new ValueFieldRefresher();
    }

    @Bean
    @ConditionalOnMissingBean
    public ConfigWatcher configWatcher(HotReloadProperties properties,
                                       ObjectProvider<ContextRefresher> contextRefresher,
                                       ObjectProvider<ValueFieldRefresher> valueFieldRefresher,
                                       ConfigurableEnvironment environment) {
        Path configFile = ExternalConfigLocationResolver.resolveConfigFile();

        ContextRefresher refresher = contextRefresher.getIfAvailable();
        if (refresher == null) {
            throw new IllegalStateException("未找到 ContextRefresher，请确认已引入 spring-cloud-context");
        }

        ConfigWatcher watcher = new ConfigWatcher(
                configFile, properties, refresher, valueFieldRefresher.getIfAvailable(), environment);
        watcher.start();

        if (watcher.isRunning()) {
            log.info("外置配置热更新组件已注册并启动，监听文件 {}", configFile);
        } else {
            log.warn("外置配置热更新组件已注册但未启动，目录不存在或无权限：{}", configFile);
        }
        return watcher;
    }
}
