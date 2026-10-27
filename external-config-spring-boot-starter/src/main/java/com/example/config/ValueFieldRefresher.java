package com.example.config;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.Ordered;
import org.springframework.core.PriorityOrdered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.convert.ConversionService;
import org.springframework.util.ReflectionUtils;
import org.springframework.util.StringUtils;

/**
 * 让 @Value 字段也支持热更新，且不销毁 Bean（对比 @RefreshScope 会重建实例、丢失状态）。
 *
 * <p>做法：Bean 初始化完成后扫描字段上的 @Value 并登记「字段 + 原始表达式 + Bean 弱引用」；
 * 配置刷新时用最新的 Environment 重新解析占位符，再通过反射回写字段。
 */
public class ValueFieldRefresher implements BeanPostProcessor, EnvironmentAware, PriorityOrdered {

    private static final Logger log = LoggerFactory.getLogger(ValueFieldRefresher.class);

    /** beanName -> (字段 -> 原始 @Value 表达式) */
    private final Map<String, Map<Field, String>> registry = new ConcurrentHashMap<>();

    /** beanName -> Bean 弱引用，避免 prototype Bean 泄漏 */
    private final Map<String, WeakReference<Object>> beans = new ConcurrentHashMap<>();

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        Class<?> type = bean.getClass();
        Map<Field, String> fields = new LinkedHashMap<>();
        ReflectionUtils.doWithFields(type, field -> {
            Value annotation = field.getAnnotation(Value.class);
            if (annotation == null || !StringUtils.hasText(annotation.value())) {
                return;
            }
            if (Modifier.isStatic(field.getModifiers())) {
                return;
            }
            if (Modifier.isFinal(field.getModifiers())) {
                log.warn("@Value 字段被 final 修饰，无法热更新：{}.{}", type.getName(), field.getName());
                return;
            }
            ReflectionUtils.makeAccessible(field);
            fields.put(field, annotation.value());
        });
        if (!fields.isEmpty()) {
            registry.put(beanName, fields);
            beans.put(beanName, new WeakReference<>(bean));
        }
        return bean;
    }

    /** 重新解析所有登记的 @Value 字段并回写，返回成功更新的字段数 */
    public int refreshAll() {
        if (environment == null || registry.isEmpty()) {
            return 0;
        }
        int updated = 0;
        for (Map.Entry<String, Map<Field, String>> beanEntry : registry.entrySet()) {
            WeakReference<Object> reference = beans.get(beanEntry.getKey());
            Object bean = reference == null ? null : reference.get();
            if (bean == null) {
                registry.remove(beanEntry.getKey());
                beans.remove(beanEntry.getKey());
                continue;
            }
            for (Map.Entry<Field, String> fieldEntry : beanEntry.getValue().entrySet()) {
                Field field = fieldEntry.getKey();
                try {
                    Object value = convert(field.getType(), environment.resolvePlaceholders(fieldEntry.getValue()));
                    ReflectionUtils.setField(field, bean, value);
                    updated++;
                } catch (RuntimeException ex) {
                    log.warn("回写 @Value 字段失败：{}.{}", bean.getClass().getName(), field.getName(), ex);
                }
            }
        }
        return updated;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object convert(Class<?> targetType, String text) {
        if (targetType == String.class) {
            return text;
        }
        String trimmed = text == null ? null : text.trim();
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.valueOf(trimmed);
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.valueOf(trimmed);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Boolean.valueOf(trimmed);
        }
        if (targetType == double.class || targetType == Double.class) {
            return Double.valueOf(trimmed);
        }
        if (targetType == float.class || targetType == Float.class) {
            return Float.valueOf(trimmed);
        }
        if (targetType == short.class || targetType == Short.class) {
            return Short.valueOf(trimmed);
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return Byte.valueOf(trimmed);
        }
        if (targetType.isEnum()) {
            return Enum.valueOf((Class<? extends Enum>) targetType, trimmed);
        }
        if (environment instanceof ConfigurableEnvironment cfg) {
            ConversionService cs = cfg.getConversionService();
            if (cs != null && cs.canConvert(String.class, targetType)) {
                return cs.convert(text, targetType);
            }
        }
        return text;
    }

    /** 已登记的 Bean 名称 */
    public Set<String> getRegisteredBeanNames() {
        return registry.keySet();
    }

    /** 已登记的字段总数 */
    public int getRegisteredFieldCount() {
        return registry.values().stream().mapToInt(Map::size).sum();
    }

    /** 移除某个 Bean 的登记 */
    public void evict(String beanName) {
        registry.remove(beanName);
        beans.remove(beanName);
    }
}
