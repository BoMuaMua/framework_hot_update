package com.example.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HotReloadPropertiesTest {

    @Test
    void 默认值与设计一致() {
        HotReloadProperties properties = new HotReloadProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getDebounceMs()).isEqualTo(300L);
        assertThat(properties.getSampleIntervalMs()).isEqualTo(150L);
        assertThat(properties.getMaxRetry()).isEqualTo(10);
        assertThat(properties.isRefreshValueFields()).isTrue();
        assertThat(properties.isVerboseLog()).isFalse();
    }
}
