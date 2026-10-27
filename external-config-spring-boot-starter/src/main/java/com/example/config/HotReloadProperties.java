package com.example.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 热更新配置，前缀 app.hot-reload，仅 6 个参数。
 */
@ConfigurationProperties(prefix = "app.hot-reload")
public class HotReloadProperties {

    /** 总开关，false 时不注册监听线程 */
    private boolean enabled = true;

    /** 收到事件后等待多久再做第一次采样，用于合并编辑器连续写盘 */
    private long debounceMs = 300L;

    /** 稳定性采样间隔 */
    private long sampleIntervalMs = 150L;

    /** 连续两次采样一致才算写完，最多采样次数，超过则放弃本次刷新 */
    private int maxRetry = 10;

    /** 刷新后是否回写 @Value 字段 */
    private boolean refreshValueFields = true;

    /** 打印采样与跳过的细节日志 */
    private boolean verboseLog = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getDebounceMs() {
        return debounceMs;
    }

    public void setDebounceMs(long debounceMs) {
        this.debounceMs = debounceMs;
    }

    public long getSampleIntervalMs() {
        return sampleIntervalMs;
    }

    public void setSampleIntervalMs(long sampleIntervalMs) {
        this.sampleIntervalMs = sampleIntervalMs;
    }

    public int getMaxRetry() {
        return maxRetry;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }

    public boolean isRefreshValueFields() {
        return refreshValueFields;
    }

    public void setRefreshValueFields(boolean refreshValueFields) {
        this.refreshValueFields = refreshValueFields;
    }

    public boolean isVerboseLog() {
        return verboseLog;
    }

    public void setVerboseLog(boolean verboseLog) {
        this.verboseLog = verboseLog;
    }
}
