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

/**
 * 外置配置文件热更新监听器。
 *
 * <p>监听「jar 同级那一个 {@code application.yml}」所在目录，事件驱动 + 时间戳校验：</p>
 * <ol>
 *   <li>WatchService 收到 ENTRY_CREATE / ENTRY_MODIFY / ENTRY_DELETE，且文件名精确匹配目标文件；</li>
 *   <li>不立即刷新，按 {@code debounce-ms} 延迟合并（编辑器保存会连发多个事件）；</li>
 *   <li>反复采样 (lastModified, size)，连续两次一致才认为写完了；</li>
 *   <li>再比内容哈希，只有内容真的变了才 {@link ContextRefresher#refresh()}；</li>
 *   <li>刷新前后各拍一次配置快照，打印「哪个键从什么变成什么、在文件第几行」。</li>
 * </ol>
 */
public class ConfigWatcher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatcher.class);

    /** 内容哈希上限，超过则退化为「时间戳变了就算变了」 */
    private static final long CONTENT_LIMIT_BYTES = 4L * 1024 * 1024;

    /** 差异打印最多显示多少行，防止一次改太多刷屏 */
    private static final int MAX_DIFF_LINES = 30;

    private static final String TOO_LARGE = "\u0000TOO_LARGE\u0000";

    /** 目标文件（可能是尚不存在的路径） */
    private final Path configFile;
    /** 目标文件名，用于在目录事件里精确比对 */
    private final String fileName;
    /** 目标文件所在目录，WatchService 只能注册目录 */
    private final Path watchDir;

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

    /** 已提交的指纹：文件名 -> {lastModified, size} */
    private volatile Map<String, long[]> committed = Map.of();
    /** 已提交的内容：文件名 -> 文本 */
    private volatile Map<String, String> committedContent = Map.of();

    private volatile long triggerCount;
    private volatile long successCount;
    private volatile long failCount;
    private volatile long skipCount;
    private volatile String lastError;
    private volatile List<String> lastChangedFiles = List.of();
    private volatile long lastRefreshEpochMs;

    public ConfigWatcher(Path configFile, HotReloadProperties properties,
                         ContextRefresher contextRefresher, ValueFieldRefresher valueFieldRefresher) {
        this(configFile, properties, contextRefresher, valueFieldRefresher, null);
    }

    public ConfigWatcher(Path configFile, HotReloadProperties properties,
                         ContextRefresher contextRefresher, ValueFieldRefresher valueFieldRefresher,
                         ConfigurableEnvironment environment) {
        this.configFile = configFile.toAbsolutePath().normalize();
        this.fileName = this.configFile.getFileName().toString();
        Path parent = this.configFile.getParent();
        this.watchDir = parent != null ? parent : this.configFile.getRoot();
        this.properties = properties;
        this.contextRefresher = contextRefresher;
        this.valueFieldRefresher = valueFieldRefresher;
        this.environment = environment;
    }

    /**
     * 启动监听。目标文件本身可以不存在，只要它所在目录存在即可。
     */
    public void start() {
        if (running.get()) {
            return;
        }
        if (watchDir == null || !Files.isDirectory(watchDir)) {
            log.warn("外置配置文件所在目录不存在，热更新不启动：{}", configFile);
            return;
        }

        committed = takeFingerprint();
        committedContent = takeContent(committed.keySet());

        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException ex) {
            log.error("创建 WatchService 失败，热更新不可用：{}", ex.getMessage());
            return;
        }

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "external-config-debounce");
            t.setDaemon(true);
            return t;
        });

        registerQuietly(watchDir);
        running.set(true);

        watchThread = new Thread(this::watchLoop, "external-config-watch-service");
        watchThread.setDaemon(true);
        watchThread.start();

        if (Files.isRegularFile(configFile)) {
            log.info("外置配置热更新已启动，监听文件 {}（{} 字节）", configFile,
                    committed.isEmpty() ? -1L : committed.values().iterator().next()[1]);
        } else {
            log.info("外置配置热更新已启动，监听目录 {}，目标文件暂不存在：{}", watchDir, fileName);
        }
    }

    private void registerQuietly(Path dir) {
        try {
            dir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_DELETE,
                    StandardWatchEventKinds.ENTRY_MODIFY);
        } catch (IOException ex) {
            log.debug("注册目录监听失败：{}", dir, ex);
        }
    }

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
                if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                    touched = true;
                    continue;
                }
                Path child = ((Path) key.watchable()).resolve((Path) event.context());
                String name = child.getFileName().toString();
                // 只认目标文件本身，编辑器产生的 .swp / .tmp / 备份文件一律忽略
                if (!name.equals(fileName)) {
                    continue;
                }
                touched = true;
                if (properties.isVerboseLog()) {
                    log.info("监听到 {} 事件：{}", event.kind().name(), name);
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

    private void scheduleCheck() {
        long gen = generation.incrementAndGet();
        if (scheduler == null || scheduler.isShutdown()) {
            return;
        }
        scheduler.schedule(() -> runCheck(gen), properties.getDebounceMs(), TimeUnit.MILLISECONDS);
    }

    private void runCheck(long gen) {
        if (gen != generation.get()) {
            // 去抖窗口内又来了新事件，本次放弃，由最后一次事件负责
            return;
        }
        if (!refreshLock.tryLock()) {
            return;
        }
        triggerCount++;
        try {
            Map<String, long[]> stable = awaitStable();
            if (stable == null) {
                log.warn("等待文件写入稳定超时（已采样 {} 次），放弃本次刷新", properties.getMaxRetry());
                return;
            }

            if (sameFingerprint(committed, stable)) {
                skipCount++;
                if (properties.isVerboseLog()) {
                    log.info("[gen={}] 时间戳与文件大小均未变化（可能只是重复保存），跳过刷新", gen);
                }
                return;
            }

            Map<String, String> stableContent = takeContent(stable.keySet());
            if (sameContent(committedContent, stableContent)) {
                skipCount++;
                committed = stable;
                committedContent = stableContent;
                log.info("[gen={}] 时间戳有变化但文件内容完全一致（touch 或空保存），跳过刷新", gen);
                return;
            }

            // 文件从「不存在」变为「存在」属于首次加载，此时全量配置都是新增，不必逐项刷屏
            boolean firstLoad = committed.isEmpty();
            List<String> changed = diffFiles(committed, stable);
            Map<String, String> before = snapshotConfigProperties();

            long start = System.currentTimeMillis();
            contextRefresher.refresh();
            int updated = properties.isRefreshValueFields() && valueFieldRefresher != null
                    ? valueFieldRefresher.refreshAll() : 0;
            long cost = System.currentTimeMillis() - start;

            Map<String, String> after = snapshotConfigProperties();

            committed = stable;
            committedContent = stableContent;
            successCount++;
            lastChangedFiles = changed;
            lastRefreshEpochMs = System.currentTimeMillis();

            if (firstLoad) {
                log.info("外置配置文件已加载，耗时 {} ms，共 {} 项生效配置，@Value 字段更新 {} 个",
                        cost, after.size(), updated);
            } else {
                log.info("外置配置刷新成功，耗时 {} ms，变更文件 {}，@Value 字段更新 {} 个", cost, changed, updated);
                printDiff(before, after);
            }
        } catch (Throwable ex) {
            failCount++;
            lastError = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            // 关键：失败时不更新 committed，修好文件后还能自动恢复
            log.error("外置配置刷新失败，保留旧配置继续运行：{}", lastError);
        } finally {
            refreshLock.unlock();
        }
    }

    // ==================== 稳定性校验 ====================

    /**
     * 反复采样，直到连续两次 (lastModified, size) 完全一致，说明写入已完成。
     *
     * @return 稳定的指纹；超过重试次数返回 null
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

    private Map<String, long[]> takeFingerprint() {
        Map<String, long[]> result = new LinkedHashMap<>(2);
        try {
            if (Files.isRegularFile(configFile)) {
                result.put(fileName, new long[]{
                        Files.getLastModifiedTime(configFile).toMillis(),
                        Files.size(configFile)});
            }
        } catch (IOException ex) {
            log.debug("读取外置配置文件属性失败：{}", configFile, ex);
        }
        return result;
    }

    private Map<String, String> takeContent(Set<String> keys) {
        Map<String, String> result = new LinkedHashMap<>(2);
        if (keys.isEmpty()) {
            return result;
        }
        try {
            if (!Files.isRegularFile(configFile)) {
                return result;
            }
            if (Files.size(configFile) > CONTENT_LIMIT_BYTES) {
                result.put(fileName, TOO_LARGE);
                return result;
            }
            result.put(fileName, Files.readString(configFile, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            result.put(fileName, TOO_LARGE);
        }
        return result;
    }

    private boolean sameFingerprint(Map<String, long[]> a, Map<String, long[]> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, long[]> entry : a.entrySet()) {
            long[] other = b.get(entry.getKey());
            if (other == null) {
                return false;
            }
            long[] value = entry.getValue();
            if (other[0] != value[0] || other[1] != value[1]) {
                return false;
            }
        }
        return true;
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
            // 文件过大时无法比较内容，保守认为「有变化」
            if (TOO_LARGE.equals(other) || TOO_LARGE.equals(entry.getValue())) {
                return false;
            }
            if (!Objects.equals(other, entry.getValue())) {
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
        for (String key : before.keySet()) {
            if (!after.containsKey(key)) {
                changed.add(key);
            }
        }
        return List.copyOf(changed);
    }

    // ==================== 差异打印 ====================

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

    private Set<String> changedKeys(Map<String, String> before, Map<String, String> after) {
        Set<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        keys.removeIf(k -> Objects.equals(before.get(k), after.get(k)));
        return keys;
    }

    private List<String> diffProperties(Map<String, String> before, Map<String, String> after) {
        Set<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());

        List<String> lines = new java.util.ArrayList<>();
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
     * 打印变化项在源文件里的行号，方便直接跳过去改。
     */
    private void printSourceLines(Set<String> changed) {
        if (changed.isEmpty() || !Files.isRegularFile(configFile)) {
            return;
        }
        List<String> hits;
        try {
            List<String> raw = Files.readAllLines(configFile, StandardCharsets.UTF_8);
            hits = fileName.endsWith(".properties")
                    ? flattenPropertiesHits(raw, changed)
                    : flattenYamlHits(raw, changed);
        } catch (IOException ex) {
            log.debug("读取源文件失败，跳过行号定位：{}", configFile, ex);
            return;
        }
        if (hits.isEmpty()) {
            return;
        }
        log.info("  来源文件 {}：", fileName);
        for (String hit : hits) {
            log.info("    {}", hit);
        }
    }

    private List<String> flattenPropertiesHits(List<String> raw, Set<String> changed) {
        List<String> hits = new java.util.ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            String trimmed = raw.get(i).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0 && changed.contains(trimmed.substring(0, eq).trim())) {
                hits.add("第 " + (i + 1) + " 行 | " + trimmed);
            }
        }
        return hits;
    }

    /**
     * 把 yaml 的缩进结构压平成 a.b.c 形式，命中变化项就返回行号 + 原文。
     */
    private List<String> flattenYamlHits(List<String> raw, Set<String> changed) {
        List<String> hits = new java.util.ArrayList<>();
        List<int[]> stack = new java.util.ArrayList<>();
        List<String> parts = new java.util.ArrayList<>();

        for (int i = 0; i < raw.size(); i++) {
            String line = raw.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            if (key.isEmpty()) {
                continue;
            }
            int indent = line.length() - line.replaceAll("^\\s+", "").length();

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

    /**
     * 拍一份「当前生效的配置项」快照。
     *
     * <p>注意：必须用 {@code environment.getProperty} 取最终生效值。
     * 如果逐 PropertySource 覆盖写入，jar 内的低优先级配置会盖掉外置配置的高优先级值，
     * 导致差异永远显示为空。</p>
     */
    private Map<String, String> snapshotConfigProperties() {
        Map<String, String> snapshot = new TreeMap<>();
        if (environment == null) {
            log.warn("Environment 不可用，本次跳过配置差异打印");
            return snapshot;
        }
        Set<String> names = new TreeSet<>();
        for (var source : environment.getPropertySources()) {
            if (source instanceof EnumerablePropertySource<?> enumerable && isConfigSource(source.getName())) {
                for (String name : enumerable.getPropertyNames()) {
                    names.add(name);
                }
            }
        }
        for (String name : names) {
            String value = environment.getProperty(name);
            snapshot.put(name, value == null ? "" : value);
        }
        return snapshot;
    }

    private boolean isConfigSource(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase();
        return lower.contains(".yml") || lower.contains(".yaml")
                || lower.contains(".properties") || lower.contains("externalconfiglocation");
    }

    // ==================== 诊断 ====================

    public boolean isRunning() {
        return running.get();
    }

    /** 目标配置文件路径 */
    public Path getConfigFile() {
        return configFile;
    }

    /** 目标配置文件所在目录 */
    public Path getConfigDir() {
        return watchDir;
    }

    public Map<String, Object> status() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("running", running.get());
        map.put("configFile", configFile.toString());
        map.put("fileExists", Files.isRegularFile(configFile));
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
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ex) {
                log.debug("关闭 WatchService 失败", ex);
            }
        }
        log.info("外置配置热更新已停止");
    }
}