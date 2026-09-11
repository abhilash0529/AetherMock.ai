package ai.aethermock.engine;

import ai.aethermock.dto.ServiceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ScenarioMatcher {

    private static final Logger log = LoggerFactory.getLogger(ScenarioMatcher.class);

    // Escaped literal Markdown asterisks properly (\* instead of dangling *)
    private static final Pattern ENDPOINT_PATTERN = Pattern
            .compile("(?i)-\\s*\\**Endpoint:\\**\\s*([A-Z]+)\\s+([^\\s\\n\\r]+)");

    private static final Pattern CONDITION_PATTERN = Pattern
            .compile("(?i)(?:Condition|Trigger Condition):?\\s*\\**\\s*([^\\n\\r]+)");

    private static final Pattern NUMERIC_COND_PATTERN = Pattern.compile(
            "(?i)([a-zA-Z0-9_]+)\\s*(?:is\\s+)?(>=|<=|>|<|==|!=|=|greater than or equal to|less than or equal to|greater than|less than|equals|is)\\s*([0-9]+(?:\\.[0-9]+)?)");

    private static final Pattern STRING_COND_PATTERN = Pattern.compile(
            "(?i)([a-zA-Z0-9_]+)\\s*(?:is|=|==|equals)\\s*[\"']([^\"']+)[\"']");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatClient chatClient;

    @Autowired
    public ScenarioMatcher(@Autowired(required = false) ChatClient.Builder chatClientBuilder,
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
     * Backward-compatible matchScenario overload.
     */
    public String matchScenario(ServiceContext context, String requestBody, Map<String, String> headers) {
        return matchScenario(context, null, null, null, requestBody, headers);
    }

    /**
     * Fully dynamic Scenario Matcher evaluating Method, URI, Query Parameters, Request Body, and AI logic.
     */
    public String matchScenario(ServiceContext context, String httpMethod, String requestUri, String queryString,
                                String requestBody, Map<String, String> headers) {

        if (context == null || context.scenarios().isEmpty()) {
            return null;
        }

        // 1. Filter candidate scenarios dynamically by Route (HTTP Method & URI path)
        Map<String, String> candidateScenarios = filterScenariosByRoute(context.scenarios(), httpMethod, requestUri);
        if (candidateScenarios.isEmpty()) {
            candidateScenarios = context.scenarios(); // Fallback to all if route filtering yields empty
        }

        // 2. Try AI-driven semantic condition matching if available
        if (chatClient != null) {
            try {
                String aiMatch = matchViaAi(candidateScenarios, httpMethod, requestUri, queryString, requestBody);
                if (aiMatch != null && context.scenarios().containsKey(aiMatch)) {
                    log.info("Spring AI dynamically resolved scenario: '{}'", aiMatch);
                    return aiMatch;
                }
            } catch (Exception e) {
                log.debug("AI trigger matching skipped: {}", e.getMessage());
            }
        }

        // 3. Deterministic evaluation of candidate Markdown Trigger Conditions
        return matchDeterministically(candidateScenarios, queryString, requestBody);
    }

    /**
     * Filters Markdown scenario files dynamically by extracting the Method and Target Endpoint.
     */
    private Map<String, String> filterScenariosByRoute(Map<String, String> scenarios, String method, String uri) {
        if (method == null || uri == null) {
            return scenarios;
        }

        Map<String, String> matches = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : scenarios.entrySet()) {
            String markdown = entry.getValue();
            Matcher matcher = ENDPOINT_PATTERN.matcher(markdown);
            if (matcher.find()) {
                String targetMethod = matcher.group(1).trim();
                String targetEndpoint = matcher.group(2).trim();

                // Replace path parameters like {userId} with regex wildcard
                String normalizedTarget = Pattern.quote(targetEndpoint).replaceAll("\\\\\\{[^}]+\\\\}", "\\\\E[^/]+\\\\Q");
                
                if (method.equalsIgnoreCase(targetMethod) && uri.matches(".*" + normalizedTarget + ".*")) {
                    matches.put(entry.getKey(), markdown);
                }
            }
        }
        return matches;
    }

    String matchDeterministically(Map<String, String> scenarios, String queryString, String requestBody) {
        JsonNode rootNode = null;
        if (requestBody != null && !requestBody.isBlank()) {
            try {
                rootNode = objectMapper.readTree(requestBody);
            } catch (Exception ignored) {
            }
        }

        // Parse query string parameters into a JSON Node for GET requests
        if (rootNode == null && queryString != null && !queryString.isBlank()) {
            Map<String, String> queryMap = parseQueryString(queryString);
            rootNode = objectMapper.valueToTree(queryMap);
        }

        List<String> scenarioKeys = new ArrayList<>(scenarios.keySet());
        scenarioKeys.sort((a, b) -> Boolean.compare(isSpecializedScenario(b), isSpecializedScenario(a)));

        for (String scenarioName : scenarioKeys) {
            String scenarioMarkdown = scenarios.get(scenarioName);
            String conditionText = extractConditionText(scenarioMarkdown);

            if (conditionText != null && !conditionText.isBlank()) {
                if (rootNode != null && evaluateCondition(conditionText, rootNode)) {
                    log.info("Request matched trigger condition for scenario '{}': [{}]", scenarioName, conditionText);
                    return scenarioName;
                } else if (conditionText.toLowerCase().contains("present") || conditionText.toLowerCase().contains("query")) {
                    log.info("Route-matched scenario without body evaluated: '{}'", scenarioName);
                    return scenarioName;
                }
            } else {
                // If scenario has no restrictive trigger condition and path matches, return as standard scenario
                return scenarioName;
            }
        }

        return null;
    }

    private boolean isSpecializedScenario(String name) {
        String n = name.toLowerCase();
        return n.contains("fraud") || n.contains("fail") || n.contains("error")
                || n.contains("reject") || n.contains("invalid") || n.contains("limit");
    }

    private String extractConditionText(String markdown) {
        Matcher matcher = CONDITION_PATTERN.matcher(markdown);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    boolean evaluateCondition(String conditionText, JsonNode rootNode) {
        String cond = conditionText.trim();

        Matcher numMatcher = NUMERIC_COND_PATTERN.matcher(cond);
        boolean hasNumericMatch = false;
        boolean numericResult = true;

        while (numMatcher.find()) {
            hasNumericMatch = true;
            String fieldName = numMatcher.group(1);
            String op = normalizeOperator(numMatcher.group(2));
            double targetVal = Double.parseDouble(numMatcher.group(3));

            JsonNode valNode = findFieldCaseInsensitive(rootNode, fieldName);
            if (valNode == null || !valNode.isNumber()) {
                numericResult = false;
                break;
            }

            if (!compareNumbers(valNode.asDouble(), op, targetVal)) {
                numericResult = false;
                break;
            }
        }

        Matcher strMatcher = STRING_COND_PATTERN.matcher(cond);
        boolean hasStringMatch = false;
        boolean stringResult = true;

        while (strMatcher.find()) {
            hasStringMatch = true;
            String fieldName = strMatcher.group(1);
            String expectedVal = strMatcher.group(2);

            JsonNode valNode = findFieldCaseInsensitive(rootNode, fieldName);
            if (valNode == null || !valNode.asText().equalsIgnoreCase(expectedVal)) {
                stringResult = false;
                break;
            }
        }

        if (hasNumericMatch || hasStringMatch) {
            return numericResult && stringResult;
        }

        // Presence check for query params (e.g. "username is present")
        String condLower = cond.toLowerCase();
        if (condLower.contains("present")) {
            for (String part : condLower.split("\\s+")) {
                if (findFieldCaseInsensitive(rootNode, part) != null) {
                    return true;
                }
            }
        }

        return false;
    }

    private JsonNode findFieldCaseInsensitive(JsonNode root, String fieldName) {
        if (root == null) return null;
        if (root.has(fieldName)) return root.get(fieldName);
        Iterator<String> fieldNames = root.fieldNames();
        while (fieldNames.hasNext()) {
            String name = fieldNames.next();
            if (name.equalsIgnoreCase(fieldName)) {
                return root.get(name);
            }
        }
        return null;
    }

    private String normalizeOperator(String rawOp) {
        String op = rawOp.trim().toLowerCase();
        return switch (op) {
            case "greater than", ">" -> ">";
            case "greater than or equal to", ">=" -> ">=";
            case "less than", "<" -> "<";
            case "less than or equal to", "<=" -> "<=";
            case "equals", "is", "==", "=" -> "==";
            case "!=" -> "!=";
            default -> "==";
        };
    }

    private boolean compareNumbers(double actual, String op, double target) {
        return switch (op) {
            case ">" -> actual > target;
            case ">=" -> actual >= target;
            case "<" -> actual < target;
            case "<=" -> actual <= target;
            case "==" -> Math.abs(actual - target) < 0.0001;
            case "!=" -> Math.abs(actual - target) >= 0.0001;
            default -> false;
        };
    }

    private Map<String, String> parseQueryString(String queryString) {
        Map<String, String> map = new HashMap<>();
        if (queryString != null && !queryString.isBlank()) {
            for (String param : queryString.split("&")) {
                String[] pair = param.split("=");
                if (pair.length > 0) {
                    map.put(pair[0], pair.length > 1 ? pair[1] : "");
                }
            }
        }
        return map;
    }

    private String matchViaAi(Map<String, String> candidateScenarios, String method, String uri,
                               String queryString, String requestBody) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Given an incoming HTTP Request:\n");
        prompt.append("Method: ").append(method != null ? method : "N/A").append("\n");
        prompt.append("URI: ").append(uri != null ? uri : "N/A").append("\n");
        prompt.append("Query String: ").append(queryString != null ? queryString : "N/A").append("\n");
        prompt.append("Body: ").append(requestBody != null ? requestBody : "N/A").append("\n\n");

        prompt.append("Evaluate against these target candidate scenarios:\n");
        for (Map.Entry<String, String> entry : candidateScenarios.entrySet()) {
            prompt.append("- Scenario: '").append(entry.getKey()).append("'\n");
            prompt.append("  Rule: ").append(entry.getValue()).append("\n");
        }

        prompt.append("\nWhich scenario key best matches this HTTP request? Return ONLY the scenario key name, or NONE.");

        String response = chatClient.prompt()
                .user(prompt.toString())
                .call()
                .content();

        if (response != null) {
            String candidate = response.trim().replaceAll("['\"`]", "");
            if (candidateScenarios.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
