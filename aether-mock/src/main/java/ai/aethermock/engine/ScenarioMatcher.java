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
    private static final Pattern CONDITION_PATTERN = Pattern.compile("(?i)(?:Condition|Trigger Condition):?\\s*\\**\\s*([^\\n\\r]+)");
    
    // Matches: Amount > 10000, Amount <= 10000.00, amount is greater than 10000, etc.
    private static final Pattern NUMERIC_COND_PATTERN = Pattern.compile(
            "(?i)([a-zA-Z0-9_]+)\\s*(?:is\\s+)?(>=|<=|>|<|==|!=|=|greater than or equal to|less than or equal to|greater than|less than|equals|is)\\s*([0-9]+(?:\\.[0-9]+)?)");
    
    // Matches: currency is "USD", currency == 'USD', currency equals "USD"
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
     * Evaluates incoming request against all scenario trigger conditions for the service.
     * Returns the matching scenario name, or null if no condition specifically matches.
     */
    public String matchScenario(ServiceContext context, String requestBody, Map<String, String> headers) {
        if (context == null || context.scenarios().isEmpty() || requestBody == null || requestBody.isBlank()) {
            return null;
        }

        // 1. Try AI-driven semantic condition matching if available
        if (chatClient != null) {
            try {
                String aiMatch = matchViaAi(context, requestBody);
                if (aiMatch != null && context.scenarios().containsKey(aiMatch)) {
                    log.info("Spring AI resolved scenario from trigger conditions: '{}'", aiMatch);
                    return aiMatch;
                }
            } catch (Exception e) {
                log.debug("AI trigger matching skipped: {}", e.getMessage());
            }
        }

        // 2. Deterministic rule-based evaluation of Markdown Trigger Conditions
        return matchDeterministically(context, requestBody);
    }

    String matchDeterministically(ServiceContext context, String requestBody) {
        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(requestBody);
        } catch (Exception e) {
            return null;
        }

        if (rootNode == null || !rootNode.isObject()) {
            return null;
        }

        Map<String, String> scenarios = context.scenarios();

        // Separate edge-case / error / fraud scenarios from happy-path/standard scenarios
        // Specific boundary/fraud conditions are evaluated with higher priority
        List<String> scenarioKeys = new ArrayList<>(scenarios.keySet());
        scenarioKeys.sort((a, b) -> {
            boolean aIsSpecial = isSpecializedScenario(a);
            boolean bIsSpecial = isSpecializedScenario(b);
            if (aIsSpecial && !bIsSpecial) return -1;
            if (!aIsSpecial && bIsSpecial) return 1;
            return 0;
        });

        for (String scenarioName : scenarioKeys) {
            String scenarioMarkdown = scenarios.get(scenarioName);
            String conditionText = extractConditionText(scenarioMarkdown);

            if (conditionText != null && !conditionText.isBlank()) {
                if (evaluateCondition(conditionText, rootNode)) {
                    log.info("Request payload matched trigger condition for scenario '{}': [{}]", scenarioName, conditionText);
                    return scenarioName;
                }
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
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    /**
     * Evaluates a condition expression against the JSON request body.
     */
    boolean evaluateCondition(String conditionText, JsonNode rootNode) {
        String cond = conditionText.trim();

        // 1. Evaluate Numeric comparisons (e.g. Amount > 10000.00, Amount <= 10000.00)
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

            double actualVal = valNode.asDouble();
            if (!compareNumbers(actualVal, op, targetVal)) {
                numericResult = false;
                break;
            }
        }

        // 2. Evaluate String equality (e.g. currency is "USD")
        Matcher strMatcher = STRING_COND_PATTERN.matcher(cond);
        boolean hasStringMatch = false;
        boolean stringResult = true;

        while (strMatcher.find()) {
            hasStringMatch = true;
            String fieldName = strMatcher.group(1);
            String expectedVal = strMatcher.group(2);

            JsonNode valNode = findFieldCaseInsensitive(rootNode, fieldName);
            if (valNode == null) {
                stringResult = false;
                break;
            }

            String actualVal = valNode.asText();
            if (!actualVal.equalsIgnoreCase(expectedVal)) {
                stringResult = false;
                break;
            }
        }

        if (hasNumericMatch || hasStringMatch) {
            return numericResult && stringResult;
        }

        // 3. Evaluate heuristic text conditions (e.g. "Email already registered or invalid domain")
        String condLower = cond.toLowerCase();
        if (condLower.contains("email") && (condLower.contains("registered") || condLower.contains("invalid") || condLower.contains("duplicate"))) {
            JsonNode emailNode = findFieldCaseInsensitive(rootNode, "email");
            if (emailNode != null) {
                String email = emailNode.asText().toLowerCase();
                return email.contains("existing") || email.contains("duplicate") 
                        || email.contains("invalid") || email.contains("fail") || email.contains("taken");
            }
        }

        return false;
    }

    private JsonNode findFieldCaseInsensitive(JsonNode root, String fieldName) {
        if (root.has(fieldName)) {
            return root.get(fieldName);
        }
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

    private String matchViaAi(ServiceContext context, String requestBody) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Given an incoming HTTP JSON request payload:\n");
        prompt.append(requestBody).append("\n\n");
        prompt.append("Evaluate against these scenarios and their trigger conditions:\n");

        for (Map.Entry<String, String> entry : context.scenarios().entrySet()) {
            prompt.append("- Scenario: '").append(entry.getKey()).append("'\n");
            prompt.append("  Rule: ").append(entry.getValue()).append("\n");
        }

        prompt.append("\nWhich scenario's condition matches this request? Return ONLY the scenario key name, or NONE.");

        String response = chatClient.prompt()
                .user(prompt.toString())
                .call()
                .content();

        if (response != null) {
            String candidate = response.trim().replaceAll("['\"`]", "");
            if (context.scenarios().containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}

