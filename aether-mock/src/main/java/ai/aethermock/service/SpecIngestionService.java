package ai.aethermock.service;

import ai.aethermock.dto.WireMockStubSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SpecIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SpecIngestionService.class);

    private final ChatClient chatClient;

    @Autowired
    public SpecIngestionService(@Autowired(required = false) ChatClient.Builder chatClientBuilder,
                                @Autowired(required = false) ChatModel chatModel) {
        if (chatClientBuilder != null) {
            this.chatClient = chatClientBuilder.build();
        } else if (chatModel != null) {
            this.chatClient = ChatClient.create(chatModel);
        } else {
            this.chatClient = null;
        }
    }

    /**
     * Synthesizes an OpenAPI specification alongside markdown scenario rules
     * into a production-ready WireMockStubSpec record.
     */
    public WireMockStubSpec synthesizeStub(String serviceName, String openApiContent, String scenarioMarkdown) {
        if (chatClient != null) {
            try {
                log.info("Invoking Spring AI ChatClient for service '{}'...", serviceName);
                // CORRECT: Line terminator immediately following """
                    String systemPrompt = """
                        You are an API virtualization engine called AetherMock.ai.
                        Given an OpenAPI contract and a Markdown scenario specification, synthesize a precise WireMock stub specification.
                        CRITICAL: Always use WireMock Handlebars templating expressions for any field that should echo the request body or path.
                        Examples:
                        - String IDs: "{{jsonPath request.body '$.transactionId'}}"
                        - Nested values: "{{jsonPath request.body '$.user.email'}}"
                        Do NOT output static values like "TXN-1001" if they are present in the request body.
                        """;

                String userPrompt = String.format("""
                    Service Name: %s
                    
                    === OPENAPI CONTRACT ===
                    %s
                    
                    === SCENARIO RULES ===
                    %s
                    
                    Generate a WireMockStubSpec matching the target API path, method, status code, headers, and templated response body.
                    """, serviceName, openApiContent, scenarioMarkdown);

                WireMockStubSpec spec = chatClient.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .entity(WireMockStubSpec.class);

                if (spec != null && spec.request() != null && spec.response() != null) {
                    log.info("Spring AI synthesized WireMock stub for endpoint '{}'", spec.request().urlPath());
                    return spec;
                }
            } catch (Exception e) {
                log.warn("Spring AI invocation encountered an error (e.g. dev/mock key or network). Falling back to deterministic synthesis engine: {}", e.getMessage());
            }
        }

        // Deterministic synthesis fallback engine (Dynamic OpenAPI & Markdown parser)
        return synthesizeDeterministically(serviceName, openApiContent, scenarioMarkdown);
    }

    /**
     * Fallback algorithmic engine that dynamically extracts OpenAPI endpoints, HTTP status codes,
     * headers, and Handlebars JSON response bodies directly from the scenario and contract without hardcoding.
     */
    WireMockStubSpec synthesizeDeterministically(String serviceName, String openApiContent, String scenarioMarkdown) {
        // 1. Extract endpoint & method
        String method = "POST";
        String urlPath = "/v1/" + serviceName;

        Pattern endpointPattern = Pattern.compile("(?i)(?:Endpoint|Target API):?\\s*\\**\\s*(GET|POST|PUT|DELETE|PATCH)\\s+([^\\s\\*]+)");
        Matcher endpointMatcher = endpointPattern.matcher(scenarioMarkdown);
        if (endpointMatcher.find()) {
            method = endpointMatcher.group(1).toUpperCase();
            urlPath = endpointMatcher.group(2).trim();
        } else {
            // Fall back to openapi paths
            Pattern pathPattern = Pattern.compile("(?m)^\\s{2}(/[a-zA-Z0-9_/#\\-]+):");
            Matcher pathMatcher = pathPattern.matcher(openApiContent);
            if (pathMatcher.find()) {
                urlPath = pathMatcher.group(1);
            }
        }

        // 2. Extract HTTP Status
        int status = 200;
        Pattern statusPattern = Pattern.compile("(?i)(?:HTTP\\s*Status|Status):?\\s*\\**\\s*(\\d{3})");
        Matcher statusMatcher = statusPattern.matcher(scenarioMarkdown);
        if (statusMatcher.find()) {
            status = Integer.parseInt(statusMatcher.group(1));
        }

        // 3. Extract Headers
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");

        Pattern headerPattern = Pattern.compile("(?i)(?:Headers):?\\s*\\**\\s*([^\n\r]+)");
        Matcher headerMatcher = headerPattern.matcher(scenarioMarkdown);
        if (headerMatcher.find()) {
            String headerLine = headerMatcher.group(1);
            for (String pair : headerLine.split(",")) {
                String[] parts = pair.split(":");
                if (parts.length == 2) {
                    headers.put(parts[0].trim(), parts[1].trim());
                }
            }
        }

        // 4. Synthesize Dynamic Response Body directly from OpenAPI schema and Markdown context
        String body = buildResponseBodyDynamic(serviceName, openApiContent, scenarioMarkdown, status);

        WireMockStubSpec.RequestMatcher requestMatcher = new WireMockStubSpec.RequestMatcher(
                method,
                urlPath,
                Collections.emptyMap()
        );

        WireMockStubSpec.ResponseDefinition responseDefinition = new WireMockStubSpec.ResponseDefinition(
                status,
                headers,
                body
        );

        return new WireMockStubSpec(requestMatcher, responseDefinition);
    }

    /**
     * Dynamically builds a response JSON payload by inspecting OpenAPI schemas and scenario specs.
     */
    private String buildResponseBodyDynamic(String serviceName, String openApiContent, String scenarioMarkdown, int status) {
        // Priority 1: Check for explicit raw JSON response blocks in the markdown scenario
        Pattern jsonBlockPattern = Pattern.compile("```json\\s*(\\{[\\s\\S]*?\\})\\s*```");
        Matcher jsonBlockMatcher = jsonBlockPattern.matcher(scenarioMarkdown);
        if (jsonBlockMatcher.find()) {
            return jsonBlockMatcher.group(1).trim();
        }

        // Priority 2: Parse "## Response Body Instructions" or "- Return JSON: ..." directly from the scenario markdown
        String fromInstructions = parseResponseBodyInstructions(scenarioMarkdown);
        if (fromInstructions != null && !fromInstructions.isBlank()) {
            return fromInstructions;
        }

        // Priority 3: Dynamically parse properties from OpenAPI YAML schema
        Map<String, String> schemaFields = extractOpenApiSchemaProperties(openApiContent, status);

        if (!schemaFields.isEmpty()) {
            StringBuilder jsonBuilder = new StringBuilder("{\n");
            int count = 0;
            int total = schemaFields.size();

            for (Map.Entry<String, String> entry : schemaFields.entrySet()) {
                String key = entry.getKey();
                String type = entry.getValue();
                String value = generateDynamicFieldValue(key, type, status, scenarioMarkdown);

                jsonBuilder.append(String.format("  \"%s\": %s", key, value));
                count++;
                if (count < total) {
                    jsonBuilder.append(",");
                }
                jsonBuilder.append("\n");
            }
            jsonBuilder.append("}");
            return jsonBuilder.toString();
        }

        // Priority 3: Generic dynamic payload fallback
        String genericStatus = status < 400 ? "SUCCESS" : "ERROR";
        return String.format("""
            {
              "status": "%s",
              "service": "%s",
              "timestamp": "{{now format='yyyy-MM-dd\\'T\\'HH:mm:ss.SSSZ'}}"
            }""", genericStatus, serviceName).trim();
    }

    /**
     * Parses natural language 'Response Body Instructions' from Markdown into Handlebars JSON.
     * Handles patterns like:
     * - Return JSON: status = "APPROVED", mirror transactionId from request, generate approvalCode = "APP-99001".
     */
    private String parseResponseBodyInstructions(String scenarioMarkdown) {
        Pattern returnJsonPattern = Pattern.compile("(?i)(?:Return\\s+JSON:?|Response\\s+Body\\s+Instructions:?)\\s*([^\n\r]+)");
        Matcher matcher = returnJsonPattern.matcher(scenarioMarkdown);
        if (!matcher.find()) {
            return null;
        }

        String instructionLine = matcher.group(1);
        Map<String, String> fields = new LinkedHashMap<>();

        // Match key = "value" or key = value
        Pattern keyValuePattern = Pattern.compile("([a-zA-Z0-9_]+)\\s*=\\s*\"([^\"]*)\"");
        Matcher kvMatcher = keyValuePattern.matcher(instructionLine);
        while (kvMatcher.find()) {
            fields.put(kvMatcher.group(1), "\"" + kvMatcher.group(2) + "\"");
        }

        // Match mirror <field> from request
        Pattern mirrorPattern = Pattern.compile("(?i)mirror\\s+([a-zA-Z0-9_]+)\\s+from\\s+request");
        Matcher mirrorMatcher = mirrorPattern.matcher(instructionLine);
        while (mirrorMatcher.find()) {
            String field = mirrorMatcher.group(1);
            fields.put(field, String.format("\"{{jsonPath request.body '$.%s'}}\"", field));
        }

        if (fields.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("{\n");
        int count = 0;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            sb.append(String.format("  \"%s\": %s", entry.getKey(), entry.getValue()));
            count++;
            if (count < fields.size()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * Extracts YAML response properties for the targeted HTTP status from the OpenAPI specification.
     */
    private Map<String, String> extractOpenApiSchemaProperties(String openApiContent, int status) {
        Map<String, String> properties = new LinkedHashMap<>();
        
        // Search for target status code block or default to schema properties
        Pattern propPattern = Pattern.compile("(?m)^\\s{8,12}([a-zA-Z0-9_]+):\\s*\\n\\s+type:\\s*([a-zA-Z]+)");
        Matcher matcher = propPattern.matcher(openApiContent);

        while (matcher.find()) {
            properties.put(matcher.group(1), matcher.group(2).toLowerCase());
        }

        // Fallback property scanning if indentation varies
        if (properties.isEmpty()) {
            Pattern simplePropPattern = Pattern.compile("(?m)^\\s+([a-zA-Z0-9_]+):\\s*\\n\\s+type:\\s*([a-zA-Z]+)");
            Matcher simpleMatcher = simplePropPattern.matcher(openApiContent);
            while (simpleMatcher.find()) {
                properties.put(simpleMatcher.group(1), simpleMatcher.group(2).toLowerCase());
            }
        }

        return properties;
    }

    /**
     * Generates Handlebars request-mirroring expressions or dynamic mock values based on key/type.
     */
    private String generateDynamicFieldValue(String key, String type, int status, String scenarioMarkdown) {
        String keyLower = key.toLowerCase();

        // Handlebars mirroring for ID/Key parameters present in requests
        if (keyLower.contains("id") || keyLower.contains("code") || keyLower.contains("key")) {
            return String.format("\"{{jsonPath request.body '$.%s'}}\"", key);
        }

        if (keyLower.equals("status")) {
            if (status >= 400 || scenarioMarkdown.toLowerCase().contains("fail") || scenarioMarkdown.toLowerCase().contains("fraud")) {
                return "\"FAILED\"";
            }
            return "\"APPROVED\"";
        }

        if (keyLower.contains("error") || keyLower.contains("message")) {
            if (status >= 400) {
                return "\"ERR_PROCESSING_FAILED\"";
            }
            return "\"Operation processed successfully\"";
        }

        return switch (type) {
            case "integer", "number" -> "1000";
            case "boolean" -> status < 400 ? "true" : "false";
            case "array" -> "[]";
            default -> String.format("\"MOCK_%s\"", key.toUpperCase());
        };
    }
}
