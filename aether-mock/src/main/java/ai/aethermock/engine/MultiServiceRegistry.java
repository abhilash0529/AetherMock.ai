package ai.aethermock.engine;

import ai.aethermock.config.WireMockConfig;
import ai.aethermock.dto.ServiceContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Component
public class MultiServiceRegistry {

    private static final Logger log = LoggerFactory.getLogger(MultiServiceRegistry.class);

    private final WireMockConfig config;
    private final ConcurrentMap<String, ServiceContext> services = new ConcurrentHashMap<>();

    public MultiServiceRegistry(WireMockConfig config) {
        this.config = config;
    }

    @PostConstruct
    public void init() {
        scanAndRegisterServices();
    }

    /**
     * Scans the configured data directory in parallel using Java 25 Virtual
     * Threads.
     */
    public void scanAndRegisterServices() {
        services.clear();
        Path baseDir = resolveDataDirectory(config.getDataDir());

        if (baseDir == null || !Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
            log.warn("Mock data directory not found or is not a directory: {}", config.getDataDir());
            return;
        }

        log.info("Scanning mock data directory: {}", baseDir.toAbsolutePath());

        try (Stream<Path> subDirs = Files.list(baseDir);
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

            List<Path> serviceDirs = subDirs.filter(Files::isDirectory).toList();
            List<Future<ServiceContext>> futures = new ArrayList<>();

            for (Path serviceDir : serviceDirs) {
                futures.add(executor.submit(() -> loadServiceContext(serviceDir)));
            }

            for (Future<ServiceContext> future : futures) {
                try {
                    ServiceContext context = future.get();
                    if (context != null) {
                        services.put(context.serviceName(), context);
                        log.info("Registered virtual service '{}' with {} scenario(s), active: '{}'",
                                context.serviceName(), context.scenarios().size(), context.activeScenario().get());
                    }
                } catch (InterruptedException | ExecutionException e) {
                    log.error("Failed to load service context in virtual thread", e);
                }
            }

        } catch (IOException e) {
            log.error("Failed to scan directory: {}", baseDir, e);
        }
    }

    private ServiceContext loadServiceContext(Path serviceDir) {
        String serviceName = serviceDir.getFileName().toString();
        String openApiContent = "";
        Map<String, String> scenarios = new ConcurrentHashMap<>();

        try (Stream<Path> files = Files.list(serviceDir)) {
            for (Path file : files.toList()) {
                String fileName = file.getFileName().toString();
                if (fileName.equalsIgnoreCase("openapi.yml") || fileName.equalsIgnoreCase("openapi.yaml")) {
                    openApiContent = Files.readString(file, StandardCharsets.UTF_8);
                } else if (fileName.endsWith(".md")) {
                    String scenarioKey = fileName.substring(0, fileName.length() - 3);
                    String scenarioContent = Files.readString(file, StandardCharsets.UTF_8);
                    scenarios.put(scenarioKey, scenarioContent);
                }
            }
        } catch (IOException e) {
            log.error("Error reading service directory: {}", serviceDir, e);
            return null;
        }

        if (openApiContent.isBlank() && scenarios.isEmpty()) {
            log.warn("Skipping empty service directory: {}", serviceDir);
            return null;
        }

        // Determine default active scenario: prefer 'standard' or first alphabetically
        String defaultScenario = scenarios.keySet().stream()
                .filter(k -> k.toLowerCase().contains("standard") || k.toLowerCase().contains("success"))
                .findFirst()
                .orElse(scenarios.keySet().stream().sorted().findFirst().orElse("default"));

        return new ServiceContext(
                serviceName,
                openApiContent,
                scenarios,
                new AtomicReference<>(defaultScenario));
    }

    private Path resolveDataDirectory(String dirPath) {
        Path path = Paths.get(dirPath);
        if (Files.exists(path)) {
            return path;
        }
        // Check relative to current working directory
        Path relativePath = Paths.get(System.getProperty("user.dir"), dirPath);
        if (Files.exists(relativePath)) {
            return relativePath;
        }
        // Try classpath resource
        try {
            ClassPathResource resource = new ClassPathResource("mock-data");
            if (resource.exists()) {
                return resource.getFile().toPath();
            }
        } catch (IOException ignored) {
        }
        return path;
    }

    public Optional<ServiceContext> getService(String serviceName) {
        return Optional.ofNullable(services.get(serviceName));
    }

    public Map<String, ServiceContext> getAllServices() {
        return Collections.unmodifiableMap(services);
    }

    public boolean setActiveScenario(String serviceName, String scenarioName) {
        ServiceContext context = services.get(serviceName);
        if (context == null) {
            return false;
        }
        if (!context.scenarios().containsKey(scenarioName)) {
            log.warn("Scenario '{}' does not exist for service '{}'. Available: {}",
                    scenarioName, serviceName, context.scenarios().keySet());
            return false;
        }
        context.activeScenario().set(scenarioName);
        log.info("Set active scenario for service '{}' to '{}'", serviceName, scenarioName);
        return true;
    }

    public void reload() {
        log.info("Rescanning and reloading all virtual services...");
        scanAndRegisterServices();
    }
}
