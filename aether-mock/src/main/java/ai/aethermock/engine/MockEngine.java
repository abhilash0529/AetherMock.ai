package ai.aethermock.engine;

import ai.aethermock.config.WireMockConfig;
import ai.aethermock.dto.WireMockStubSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.extension.responsetemplating.ResponseTemplateTransformer;
import com.github.tomakehurst.wiremock.extension.responsetemplating.TemplateEngine;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MockEngine {

    private static final Logger log = LoggerFactory.getLogger(MockEngine.class);

    private final WireMockConfig config;
    private final ObjectMapper objectMapper;
    private WireMockServer wireMockServer;
    private final Map<String, StubMapping> activeStubMappings = new ConcurrentHashMap<>();

    public MockEngine(WireMockConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @PostConstruct
    public void start() {
        int port = config.getWiremock().getPort();
        log.info("Initializing embedded WireMockServer on port {}...", port);

        try {
            WireMockConfiguration options = WireMockConfiguration.options()
                    .port(port)
                    .extensions(new ResponseTemplateTransformer(
                            TemplateEngine.defaultTemplateEngine(),
                            true,
                            null,
                            java.util.Collections.emptyList()
                    ));

            wireMockServer = new WireMockServer(options);
            wireMockServer.start();
            log.info("Embedded WireMockServer started successfully on port: {}", wireMockServer.port());
        } catch (Exception e) {
            log.error("Failed to start embedded WireMockServer on port {}", port, e);
        }
    }

    @PreDestroy
    public void stop() {
        if (wireMockServer != null && wireMockServer.isRunning()) {
            log.info("Stopping WireMockServer...");
            wireMockServer.stop();
        }
    }

    /**
     * Translates a WireMockStubSpec record into a WireMock StubMapping instance
     * and registers it dynamically in the WireMock engine.
     */
    public StubMapping registerStub(String serviceName, String scenarioName, WireMockStubSpec spec) {
        if (wireMockServer == null || !wireMockServer.isRunning()) {
            log.warn("WireMockServer is not running; skipping in-memory registration for service '{}'", serviceName);
            return null;
        }

        String targetUrl = spec.request().urlPath();
        String method = spec.request().method().toUpperCase();

        // Support both direct path and prefixed path /mock/{serviceName}/*
        String prefixedUrl = "/mock/" + serviceName + (targetUrl.startsWith("/") ? targetUrl : "/" + targetUrl);

        MappingBuilder mappingBuilder = createMappingBuilder(method, prefixedUrl, targetUrl);

        ResponseDefinitionBuilder responseBuilder = WireMock.aResponse()
                .withStatus(spec.response().status())
                .withBody(spec.response().body())
                .withTransformers("response-template");

        if (spec.response().headers() != null) {
            for (Map.Entry<String, String> header : spec.response().headers().entrySet()) {
                responseBuilder.withHeader(header.getKey(), header.getValue());
            }
        }

        mappingBuilder.willReturn(responseBuilder);
        StubMapping stubMapping = wireMockServer.stubFor(mappingBuilder);

        String cacheKey = serviceName + ":" + scenarioName;
        activeStubMappings.put(cacheKey, stubMapping);

        // Also persist stub mapping to disk
        persistMappingToDisk(serviceName, scenarioName, spec);

        log.info("Registered WireMock stub [{}] '{}' (status {}) for service '{}' [scenario: '{}']",
                method, prefixedUrl, spec.response().status(), serviceName, scenarioName);

        return stubMapping;
    }

    private MappingBuilder createMappingBuilder(String method, String prefixedUrl, String targetUrl) {
        // Regex pattern to match either /mock/{serviceName}/path or /path
        String pathRegex = ".*(" + PatternQuote(prefixedUrl) + "|" + PatternQuote(targetUrl) + ").*";

        return switch (method) {
            case "POST" -> WireMock.post(WireMock.urlMatching(pathRegex));
            case "GET" -> WireMock.get(WireMock.urlMatching(pathRegex));
            case "PUT" -> WireMock.put(WireMock.urlMatching(pathRegex));
            case "DELETE" -> WireMock.delete(WireMock.urlMatching(pathRegex));
            case "PATCH" -> WireMock.patch(WireMock.urlMatching(pathRegex));
            default -> WireMock.any(WireMock.urlMatching(pathRegex));
        };
    }

    private String PatternQuote(String s) {
        return s.replace(".", "\\.").replace("?", "\\?");
    }

    private void persistMappingToDisk(String serviceName, String scenarioName, WireMockStubSpec spec) {
        try {
            Path mappingsDir = Paths.get("src/main/resources/wiremock/mappings");
            if (!Files.exists(mappingsDir)) {
                Files.createDirectories(mappingsDir);
            }
            String fileName = String.format("%s_%s.json", serviceName, scenarioName);
            Path filePath = mappingsDir.resolve(fileName);
            String json = objectMapper.writeValueAsString(spec);
            Files.writeString(filePath, json, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("Persisted stub mapping to: {}", filePath.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Could not persist stub mapping to disk for {}_{}: {}", serviceName, scenarioName, e.getMessage());
        }
    }

    public WireMockServer getWireMockServer() {
        return wireMockServer;
    }

    public int getPort() {
        return wireMockServer != null && wireMockServer.isRunning() ? wireMockServer.port() : config.getWiremock().getPort();
    }
}
