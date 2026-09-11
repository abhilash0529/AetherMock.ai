package ai.aethermock.dto;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public record ServiceContext(
    String serviceName,
    String openApiContent,
    Map<String, String> scenarios,
    AtomicReference<String> activeScenario
) {}

