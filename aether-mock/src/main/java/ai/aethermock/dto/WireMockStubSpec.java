package ai.aethermock.dto;

import java.util.Map;

public record WireMockStubSpec(
    RequestMatcher request,
    ResponseDefinition response
) {
    public record RequestMatcher(
        String method,
        String urlPath,
        Map<String, Object> bodyPatterns
    ) {}

    public record ResponseDefinition(
        int status,
        Map<String, String> headers,
        String body
    ) {}
}

