package com.example.app;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.config.ConfigWatcher;

@RestController
@RequestMapping("/demo")
public class DemoController {

    private final DemoProperties properties;
    private final ConfigWatcher configWatcher;

    /** 用于验证 @Value 字段是否随外置配置热更新 */
    @Value("${demo.app-name:未注入}")
    private String appNameByValue;

    @Value("${demo.timeout-ms:0}")
    private long timeoutMsByValue;

    public DemoController(DemoProperties properties, ConfigWatcher configWatcher) {
        this.properties = properties;
        this.configWatcher = configWatcher;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> map = new LinkedHashMap<>();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("appName", properties.getAppName());
        props.put("featureEnabled", properties.isFeatureEnabled());
        props.put("timeoutMs", properties.getTimeoutMs());
        map.put("configurationProperties", props);

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("appName", appNameByValue);
        values.put("timeoutMs", timeoutMsByValue);
        map.put("valueAnnotation", values);
        return map;
    }

    @GetMapping("/hot-reload/status")
    public Map<String, Object> status() {
        return configWatcher.status();
    }
}
