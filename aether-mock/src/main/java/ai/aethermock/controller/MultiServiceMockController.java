package ai.aethermock.controller;

import ai.aethermock.dto.ServiceContext;
import ai.aethermock.dto.WireMockStubSpec;
import ai.aethermock.engine.MockEngine;
import ai.aethermock.engine.MultiServiceRegistry;
import ai.aethermock.engine.ScenarioMatcher;
import ai.aethermock.service.SpecIngestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/mock")
public class MultiServiceMockController {

    private static final Logger log = LoggerFactory.getLogger(MultiServiceMockController.class);
    private static final String SCENARIO_HEADER = "X-Aether-Scenario";

    // Handlebars Patterns for both Request Body and Query Parameters
    private static final Pattern JSON_PATH_PATTERN = Pattern
            .compile("\\{\\{jsonPath\\s+request\\.body\\s+'\\$\\.([a-zA-Z0-9_]+)'\\}\\}");
    private static final Pattern QUERY_PARAM_PATTERN = Pattern
            .compile("\\{\\{request\\.query\\.([a-zA-Z0-9_]+)\\}\\}");

    private final MultiServiceRegistry registry;
    private final SpecIngestionService ingestionService;
    private final MockEngine mockEngine;
    private final ScenarioMatcher scenarioMatcher;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Cache synthesized specs using serviceName + scenario
    private final Map<String, WireMockStubSpec> stubCache = new ConcurrentHashMap<>();

    public MultiServiceMockController(MultiServiceRegistry registry,
            SpecIngestionService ingestionService,
            MockEngine mockEngine,
            ScenarioMatcher scenarioMatcher) {
        this.registry = registry;
        this.ingestionService = ingestionService;
        this.mockEngine = mockEngine;
        this.scenarioMatcher = scenarioMatcher;
    }

    public void clearCache() {
        stubCache.clear();
        log.info("Cleared synthesized WireMock stub cache");
    }

    @RequestMapping(value = "/{serviceName}/**", method = {
            RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
            RequestMethod.DELETE, RequestMethod.PATCH
    })
    public ResponseEntity<String> handleMockRequest(
            @PathVariable String serviceName,
            @RequestHeader(value = SCENARIO_HEADER, required = false) String scenarioHeader,
            @RequestBody(required = false) String requestBody,
            HttpServletRequest request) {

        String method = request.getMethod();
        String uri = request.getRequestURI();
        log.info("Received [{}] mock request for service '{}' at URI '{}'", method, serviceName, uri);

        // 1. Resolve virtual service context
        ServiceContext context = registry.getService(serviceName).orElse(null);
        if (context == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(String.format("{\"error\": \"Virtual service '%s' not registered in AetherMock\"}",
                            serviceName));
        }

        // 2. Extract request headers
        Map<String, String> requestHeaders = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String hName = headerNames.nextElement();
            requestHeaders.put(hName, request.getHeader(hName));
        }

        // 3. Determine scenario precedence
        String resolvedScenario;
        if (scenarioHeader != null && !scenarioHeader.isBlank()) {
            resolvedScenario = scenarioHeader.trim();
            log.info("Scenario resolved via [{}] header override: '{}'", SCENARIO_HEADER, resolvedScenario);
        } else {
            // Pass Method, URI, Query String, Body, and Headers to scenarioMatcher
            String matchedScenario = scenarioMatcher.matchScenario(
                    context,
                    method,
                    uri,
                    request.getQueryString(),
                    requestBody,
                    requestHeaders);

            if (matchedScenario != null && context.scenarios().containsKey(matchedScenario)) {
                resolvedScenario = matchedScenario;
                log.info("Scenario dynamically matched via Route & Trigger Condition: '{}'", resolvedScenario);
            } else {
                resolvedScenario = context.activeScenario().get();
                log.info("Scenario resolved via active state fallback for service '{}': '{}'", serviceName,
                        resolvedScenario);
            }
        }

        // 4. Obtain or synthesize WireMock stub spec
        String cacheKey = serviceName + ":" + resolvedScenario;
        WireMockStubSpec spec = stubCache.computeIfAbsent(cacheKey, k -> {
            String scenarioMarkdown = context.scenarios().getOrDefault(resolvedScenario, "");
            log.info("Synthesizing stub for service '{}' and scenario '{}'...", serviceName, resolvedScenario);
            WireMockStubSpec synthesized = ingestionService.synthesizeStub(serviceName, context.openApiContent(),
                    scenarioMarkdown);
            mockEngine.registerStub(serviceName, resolvedScenario, synthesized);
            return synthesized;
        });

        // 5. Render dynamic response body (Handles both JSONPath body and Query
        // Parameters)
        String renderedBody = renderTemplate(spec.response().body(), requestBody, request);

        // 6. Construct HTTP response
        HttpHeaders headers = new HttpHeaders();
        if (spec.response().headers() != null) {
            spec.response().headers().forEach(headers::add);
        }
        headers.add("X-Aether-Service", serviceName);
        headers.add("X-Aether-Scenario", resolvedScenario);
        headers.add("X-Aether-Engine", "AetherMock.ai WireMock V3");

        return ResponseEntity.status(spec.response().status())
                .headers(headers)
                .body(renderedBody);
    }

    /**
     * Resolves Handlebars placeholders dynamically:
     * - {{jsonPath request.body '$.key'}} for POST/PUT/PATCH JSON bodies
     * - {{request.query.paramName}} for GET query parameters
     */
    private String renderTemplate(String templateBody, String requestBody, HttpServletRequest request) {
        if (templateBody == null || templateBody.isBlank()) {
            return "";
        }

        String rendered = templateBody;

        // 1. Process JSONPath request body substitutions
        if (requestBody != null && !requestBody.isBlank()) {
            try {
                JsonNode rootNode = objectMapper.readTree(requestBody);
                if (rootNode != null) {
                    Matcher matcher = JSON_PATH_PATTERN.matcher(rendered);
                    StringBuilder sb = new StringBuilder();
                    while (matcher.find()) {
                        String fieldName = matcher.group(1);
                        JsonNode fieldNode = rootNode.get(fieldName);
                        String replacement = fieldNode != null ? fieldNode.asText() : "";
                        matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                    }
                    matcher.appendTail(sb);
                    rendered = sb.toString();
                }
            } catch (Exception ignored) {
                // Return untouched template if request body parsing fails
            }
        }

        // 2. Process query parameter substitutions for GET requests
        Matcher queryMatcher = QUERY_PARAM_PATTERN.matcher(rendered);
        StringBuilder querySb = new StringBuilder();
        while (queryMatcher.find()) {
            String paramName = queryMatcher.group(1);
            String paramValue = request.getParameter(paramName);
            String replacement = paramValue != null ? paramValue : "";
            queryMatcher.appendReplacement(querySb, Matcher.quoteReplacement(replacement));
        }
        queryMatcher.appendTail(querySb);

        return querySb.toString();
    }
}
