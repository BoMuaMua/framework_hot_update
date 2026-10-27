package com.example.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.context.refresh.ContextRefresher;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

/**
 * 外置配置热更新：WatchService 事件驱动 + 时间戳/文件大小二次校验。
 *
 * <p>完整链路：
 * <ol>
 *   <li>WatchService 收到 ENTRY_CREATE / ENTRY_DELETE / ENTRY_MODIFY，只当作「有动静」；</li>
 *   <li>不立即刷新，登记一个 debounceMs 后的去抖任务（新事件到来则取消旧任务，代次递增）；</li>
 *   <li>去抖到期后进入稳定性采样：每 sampleIntervalMs 采一次 (lastModified, size) 全目录指纹，
 *       连续两次完全一致才认为写入结束，最多 maxRetry 次；</li>
 *   <li>稳定后再与「上次已提交的指纹」比较，元数据完全一致说明只是重复保存，跳过；</li>
 *   <li>元数据变了但文件内容一字未改（编辑器 touch、空保存），同样跳过；</li>
 *   <li>确有内容变化才调用 {@link ContextRefresher#refresh()}。</li>
 * </ol>
 *
 * <p>时间戳不是被监视的对象，而是事件之后的校验依据，用来解决编辑器双写、半截文件、重复触发。
 */
public class ConfigWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);

    private static final String[] INCLUDE_SUFFIXES = {".yml", ".yaml", ".properties"};

    private static final String[] EXCLUDE_SUFFIXES = {".tmp", ".temp", ".swp", ".swx", ".bak", "~"};

    /** 内容校验上限：超过此大小的文件不再逐字节比对，直接按变化处理 */
    private static final long CONTENT_LIMIT_BYTES = 4L * 1024 * 1024;

    /** 差异日志最多打印多少行 */
    private static final int MAX_DIFF_LINES = 30;

    /** 内容超出比对上限的哨兵值 */
    private static final String TOO_LARGE = "\u0000TOO_LARGE\u0000";

    private final Path configDir;
    private final HotReloadProperties properties;
    private final ContextRefresher contextRefresher;
    private final ValueFieldRefresher valueFieldRefresher;
    private final ConfigurableEnvironment environment;

    private final ReentrantLock refreshLock = new ReentrantLock();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong generation = new AtomicLong(0L);

    private WatchService watchService;
    private Thread watchThread;
    private ScheduledExecutorService scheduler;

    /** 上次成功刷新时（或启动基线）的目录指纹，键为相对路径 */
    private volatile Map<String, long[]> committed = Map.of();

    /** 与 committed 对应的文件内容快照，仅用于区分「touch 但内容没变」与「真的改了」 */
    private volatile Map<String, String> committedContent = Map.of();

    private volatile long triggerCount;
    private volatile long successCount;
    private volatile long failCount;
    private volatile long skipCount;
    private volatile String lastError;
    private volatile List<String> lastChangedFiles = List.of();
    private volatile long lastRefreshEpochMs;

    public ConfigWatcher(Path configDir, HotReloadProperties properties,
                         ContextRefresher contextRefresher, ValueFieldRefresher valueFieldRefresher) {
        this(configDir, properties, contextRefresher, valueFieldRefresher, null);
    }

    public ConfigWatcher(Path configDir, HotReloadProperties properties,
                         ContextRefresher contextRefresher, ValueFieldRefresher valueFieldRefresher,
                         ConfigurableEnvironment environment) {
        this.configDir = configDir;
        this.properties = properties;
        this.contextRefresher = contextRefresher;
        this.valueFieldRefresher = valueFieldRefresher;
        this.environment = environment;
    }

    /** 启动监听：先建基线指纹，再开 WatchService 与去抖调度器 */
    public synchronized void start() {
        if (running.get()) {
            return;
        }
        if (!Files.isDirectory(configDir)) {
            log.warn("外置配置目录不存在，热更新不启动：{}", configDir);
            return;
        }

        this.committed = takeFingerprint();
        this.committedContent = takeContent(committed.keySet());
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException ex) {
            log.error("创建 WatchService 失败，热更新不可用", ex);
            return;
        }

        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "external-config-debounce");
            t.setDaemon(true);
            return t;
        });

        registerAll(configDir);
        this.running.set(true);
        this.watchThread = new Thread(this::watchLoop, "external-config-watch-service");
        this.watchThread.setDaemon(true);
        this.watchThread.start();
        log.info("外置配置热更新已启动，目录 {}，已登记 {} 个配置文件", configDir, committed.size());
    }

    // ---------------- WatchService ----------------

    private void watchLoop() {
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (ClosedWatchServiceException ex) {
                return;
            }

            boolean touched = false;
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) {
                    touched = true;
                    continue;
                }
                Path dir = (Path) key.watchable();
                Path child = dir.resolve((Path) event.context());
                if (Files.isDirectory(child)) {
                    // 新建子目录：补注册，并视作变化
                    registerAll(child);
                    touched = true;
                    continue;
                }
                if (isConfigFile(child)) {
                    touched = true;
                    if (properties.isVerboseLog()) {
                        log.info("监听到 {} 事件：{}", kind.name(), configDir.relativize(child));
                    }
                }
            }

            if (!key.reset()) {
                return;
            }
            if (touched) {
                scheduleCheck();
            }
        }
    }

    private void registerAll(Path dir) {
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isDirectory).forEach(this::registerQuietly);
        } catch (IOException ex) {
            log.debug("遍历目录失败：{}", dir, ex);
        }
    }

    private void registerQuietly(Path dir) {
        try {
            dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException ex) {
            log.debug("注册监听失败：{}", dir, ex);
        }
    }

    // ---------------- 去抖 ----------------

    private void scheduleCheck() {
        long gen = generation.incrementAndGet();
        scheduler.schedule(() -> runCheck(gen), properties.getDebounceMs(), TimeUnit.MILLISECONDS);
    }

    private void runCheck(long gen) {
        if (gen != generation.get()) {
            return; // 期间又有新事件，本次作废
        }
        if (!refreshLock.tryLock()) {
            return; // 上一轮还在刷新
        }
        try {
            triggerCount++;
            Map<String, long[]> stable = awaitStable();
            if (stable == null) {
                log.warn("等待文件写入稳定超时（已采样 {} 次），放弃本次刷新", properties.getMaxRetry());
                return;
            }
            Map<String, String> stableContent = takeContent(stable.keySet());

            if (sameFingerprint(committed, stable)) {
                skipCount++;
                if (properties.isVerboseLog()) {
                    log.info("[gen={}] 时间戳与文件大小均未变化（可能只是重复保存），跳过刷新", gen);
                }
                return;
            }
            if (sameContent(committedContent, stableContent)) {
                // 时间戳变了但内容一字未改（典型：编辑器 touch、保存但没改动、只改了换行）
                skipCount++;
                committed = stable;
                committedContent = stableContent;
                log.info("[gen={}] 时间戳有变化但文件内容完全一致（touch 或空保存），跳过刷新", gen);
                return;
            }

            long begin = System.nanoTime();
            List<String> changed = diffFiles(committed, stable);
            Map<String, String> before = snapshotConfigProperties();
            try {
                contextRefresher.refresh();
                int updated = properties.isRefreshValueFields() && valueFieldRefresher != null
                        ? valueFieldRefresher.refreshAll() : 0;
                Map<String, String> after = snapshotConfigProperties();
                committed = stable;
                committedContent = stableContent;
                successCount++;
                lastChangedFiles = changed;
                lastRefreshEpochMs = System.currentTimeMillis();
                long cost = (System.nanoTime() - begin) / 1_000_000L;
                log.info("外置配置刷新成功，耗时 {} ms，变更文件 {}，@Value 字段更新 {} 个", cost, changed, updated);
                printDiff(before, after);
            } catch (Throwable ex) {
                // 非法 YAML 等：保持旧配置继续运行，不更新 committed，修复后可自动恢复
                failCount++;
                lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
                log.error("外置配置刷新失败，保留旧配置继续运行：{}", lastError);
            }
        } finally {
            refreshLock.unlock();
        }
    }

    // ---------------- 配置差异打印 ----------------

    /** 打印本次刷新前后「实际生效的配置项」差异，便于测试阶段直观看到改了什么 */
    private void printDiff(Map<String, String> before, Map<String, String> after) {
        List<String> lines = diffProperties(before, after);
        if (lines.isEmpty()) {
            log.info("生效配置项无差异（改的可能是不生效的键，例如 server.port）");
            return;
        }
        int limit = Math.min(lines.size(), MAX_DIFF_LINES);
        log.info("检测到 {} 项配置变化：", lines.size());
        for (int i = 0; i < limit; i++) {
            log.info("    {}", lines.get(i));
        }
        if (lines.size() > limit) {
            log.info("    ...（其余 {} 项已省略）", lines.size() - limit);
        }
        printSourceLines(changedKeys(before, after));
    }

    private static Set<String> changedKeys(Map<String, String> before, Map<String, String> after) {
        Set<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        keys.removeIf(k -> Objects.equals(before.get(k), after.get(k)));
        return keys;
    }

    /**
     * 把变化项反查回「哪个文件的第几行、原文长什么样」，测试阶段定位用。
     *
     * <p>按缩进把 yml 的层级压成 a.b.c 形式，再与变化的键比对；
     * .properties 直接按 key=value 解析。
     */
    private void printSourceLines(Set<String> changed) {
        if (changed.isEmpty()) {
            return;
        }
        for (String relative : committed.keySet()) {
            Path file = configDir.resolve(relative);
            List<String> raw;
            try {
                raw = Files.readAllLines(file, StandardCharsets.UTF_8);
            } catch (IOException ex) {
                continue;
            }
            List<String> hits = new ArrayList<>();
            if (relative.endsWith(".properties")) {
                for (int i = 0; i < raw.size(); i++) {
                    String trimmed = raw.get(i).trim();
                    int eq = trimmed.indexOf('=');
                    if (eq > 0 && changed.contains(trimmed.substring(0, eq).trim())) {
                        hits.add("第 " + (i + 1) + " 行 | " + trimmed);
                    }
                }
            } else {
                hits.addAll(flattenYamlHits(raw, changed));
            }
            if (!hits.isEmpty()) {
                log.info("  来源文件 {}：", relative);
                for (String hit : hits) {
                    log.info("    {}", hit);
                }
            }
        }
    }

    /** 缩进解析 yml，返回「行号 | 原文」形式的命中项 */
    private static List<String> flattenYamlHits(List<String> raw, Set<String> changed) {
        List<String> hits = new ArrayList<>();
        List<int[]> stack = new ArrayList<>();   // [indent, indexIntoParts]
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String line = raw.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            int indent = line.length() - line.replaceAll("^\s+", "").length();
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            while (!stack.isEmpty() && stack.get(stack.size() - 1)[0] >= indent) {
                int[] popped = stack.remove(stack.size() - 1);
                while (parts.size() > popped[1]) {
                    parts.remove(parts.size() - 1);
                }
            }
            parts.add(key);
            String fullKey = String.join(".", parts);
            if (changed.contains(fullKey)) {
                hits.add("第 " + (i + 1) + " 行 | " + trimmed);
            }
            stack.add(new int[]{indent, parts.size() - 1});
        }
        return hits;
    }

    private List<String> diffProperties(Map<String, String> before, Map<String, String> after) {
        Set<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        List<String> lines = new ArrayList<>();
        for (String key : keys) {
            String oldValue = before.get(key);
            String newValue = after.get(key);
            if (Objects.equals(oldValue, newValue)) {
                continue;
            }
            if (oldValue == null) {
                lines.add(key + " = " + newValue + "   [新增]");
            } else if (newValue == null) {
                lines.add(key + " = " + oldValue + "   [删除]");
            } else {
                lines.add(key + ": " + oldValue + "  ->  " + newValue);
            }
        }
        return lines;
    }

    /**
     * 快照当前「配置文件贡献」的属性。只取名字里带 .yml/.yaml/.properties 的
     * PropertySource，避开系统环境变量等与配置文件无关的来源。
     */
    private Map<String, String> snapshotConfigProperties() {
        Map<String, String> result = new TreeMap<>();
        if (environment == null) {
            log.warn("Environment 不可用，本次跳过配置差异打印");
            return result;
        }

        Set<String> names = new TreeSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable && isConfigSource(source.getName())) {
                names.addAll(List.of(enumerable.getPropertyNames()));
            }
        }
        // 用 environment.getProperty 取「最终生效值」，而不是逐 source 覆盖——
        // 后者会让低优先级的 jar 内配置盖掉高优先级的外置配置
        for (String name : names) {
            String value = environment.getProperty(name);
            result.put(name, value == null ? "" : value);
        }
        return result;
    }

    private static boolean isConfigSource(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.contains(".yml") || lower.contains(".yaml") || lower.contains(".properties")
                || lower.contains("externalconfiglocation");
    }

    // ---------------- 稳定性校验 ----------------

    /**
     * 连续两次采样 (lastModified, size) 完全一致才返回该指纹；超过 maxRetry 返回 null。
     */
    private Map<String, long[]> awaitStable() {
        Map<String, long[]> previous = takeFingerprint();
        for (int i = 0; i < properties.getMaxRetry(); i++) {
            try {
                Thread.sleep(properties.getSampleIntervalMs());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return null;
            }
            Map<String, long[]> current = takeFingerprint();
            if (sameFingerprint(previous, current)) {
                return current;
            }
            if (properties.isVerboseLog()) {
                log.info("文件仍在写入（第 {} 次采样），继续等待", i + 1);
            }
            previous = current;
        }
        return null;
    }

    /** 采集当前目录下所有配置文件的 (lastModified, size) 指纹 */
    private Map<String, long[]> takeFingerprint() {
        Map<String, long[]> result = new HashMap<>();
        try (var stream = Files.walk(configDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(this::isConfigFile)
                    .forEach(p -> {
                        try {
                            result.put(configDir.relativize(p).toString().replace('\\', '/'),
                                    new long[]{Files.getLastModifiedTime(p).toMillis(), Files.size(p)});
                        } catch (IOException ex) {
                            log.debug("读取文件属性失败：{}", p, ex);
                        }
                    });
        } catch (IOException ex) {
            log.debug("遍历配置目录失败：{}", configDir, ex);
        }
        return result;
    }

    /**
     * 采集指定文件的文本内容，用于区分「时间戳变了但内容没变」。
     *
     * <p>不引入哈希算法：直接比对原始字符串，配置文件通常只有几 KB，
     * 逐字符比较的开销远小于一次 Spring 上下文刷新。
     * 超过 {@link #CONTENT_LIMIT_BYTES} 的文件记为 {@link #TOO_LARGE}，永远视为不同。
     */
    private Map<String, String> takeContent(Set<String> keys) {
        Map<String, String> result = new HashMap<>();
        for (String key : keys) {
            Path file = configDir.resolve(key);
            try {
                if (Files.size(file) > CONTENT_LIMIT_BYTES) {
                    result.put(key, TOO_LARGE);
                } else {
                    result.put(key, Files.readString(file, StandardCharsets.UTF_8));
                }
            } catch (IOException ex) {
                result.put(key, TOO_LARGE);
            }
        }
        return result;
    }

    private boolean sameContent(Map<String, String> a, Map<String, String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, String> entry : a.entrySet()) {
            String other = b.get(entry.getKey());
            if (other == null) {
                return false;
            }
            if (TOO_LARGE.equals(entry.getValue()) || TOO_LARGE.equals(other)) {
                return false; // 超大文件无法比对，保守判定为有变化
            }
            if (!entry.getValue().equals(other)) {
                return false;
            }
        }
        return true;
    }

    private boolean sameFingerprint(Map<String, long[]> a, Map<String, long[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, long[]> entry : a.entrySet()) {
            long[] other = b.get(entry.getKey());
            if (other == null || other[0] != entry.getValue()[0] || other[1] != entry.getValue()[1]) {
                return false;
            }
        }
        return true;
    }

    private List<String> diffFiles(Map<String, long[]> before, Map<String, long[]> after) {
        Set<String> changed = new LinkedHashSet<>();
        for (Map.Entry<String, long[]> entry : after.entrySet()) {
            long[] old = before.get(entry.getKey());
            if (old == null || old[0] != entry.getValue()[0] || old[1] != entry.getValue()[1]) {
                changed.add(entry.getKey());
            }
        }
        for (String name : before.keySet()) {
            if (!after.containsKey(name)) {
                changed.add(name);
            }
        }
        return new ArrayList<>(changed);
    }

    private boolean isConfigFile(Path path) {
        String name = path.getFileName().toString();
        if (name.startsWith(".") || name.startsWith("#") || name.endsWith("~")) {
            return false;
        }
        for (String suffix : EXCLUDE_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return false;
            }
        }
        for (String suffix : INCLUDE_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    // ---------------- 诊断 ----------------

    public boolean isRunning() {
        return running.get();
    }

    public Path getConfigDir() {
        return configDir;
    }

    public Map<String, Object> status() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("running", running.get());
        map.put("configDir", configDir.toString());
        map.put("fileCount", committed.size());
        map.put("triggerCount", triggerCount);
        map.put("successCount", successCount);
        map.put("failCount", failCount);
        map.put("skipCount", skipCount);
        map.put("lastChangedFiles", lastChangedFiles);
        map.put("lastRefreshTime", lastRefreshEpochMs == 0 ? null : lastRefreshEpochMs);
        map.put("lastError", lastError);
        return map;
    }

    @Override
    public synchronized void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        if (watchThread != null) {
            watchThread.interrupt();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        try {
            if (watchService != null) {
                watchService.close();
            }
        } catch (IOException ex) {
            log.debug("关闭 WatchService 失败", ex);
        }
        log.info("外置配置热更新已停止");
    }
}
