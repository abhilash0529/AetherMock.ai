# 📋 Requirements & Architectural Compliance Matrix

## System Specifications
- **Operating System:** macOS Apple Silicon (aarch64)
- **Runtime:** Amazon Corretto OpenJDK 25.0.4+ LTS
- **Core Framework:** Spring Boot 4.1.0+ / 4.1.1
- **AI Synthesis Engine:** Spring AI 1.1.0 (`spring-ai-starter-model-openai`)
- **Virtualization Layer:** WireMock Standalone 3.9.1 with Response Templating (Handlebars)

---

## Functional Requirements Checklist

| ID | Requirement | Implementation | Status |
|---|---|---|:---:|
| **REQ-01** | Multi-Service Registry with Virtual Thread scanning | `MultiServiceRegistry.java` using `Executors.newVirtualThreadPerTaskExecutor()` | ✅ Verified |
| **REQ-02** | Automatic discovery of `openapi.yml` & `*.md` | Scans `aethermock.data-dir` on startup and on `/admin/reload` | ✅ Verified |
| **REQ-03** | Dynamic WireMock stub generation | `MockEngine.java` using `WireMockServer` (port 8089) with response-template extension | ✅ Verified |
| **REQ-04** | Spring AI structured synthesis with Handlebars | `SpecIngestionService.java` with structured output & deterministic fallback | ✅ Verified |
| **REQ-05** | Mock Ingestion Gateway under `/mock/{serviceName}/**` | `MultiServiceMockController.java` supporting all HTTP verbs | ✅ Verified |
| **REQ-06** | Scenario Precedence (Header > Active State) | Evaluates `X-Aether-Scenario` header before falling back to `context.activeScenario().get()` | ✅ Verified |
| **REQ-07** | Admin Control Plane | `AdminController.java` (`GET /admin/services`, `POST /scenario`, `POST /reload`) | ✅ Verified |
| **REQ-08** | Dynamic Handlebars value mirroring | Mirrors `request.body` JSON properties (`transactionId`, `userId`) dynamically | ✅ Verified |
| **REQ-09** | Stub persistence | Writes serialized stub mappings to `src/main/resources/wiremock/mappings/` | ✅ Verified |
| **REQ-10** | End-to-End Automated Test Coverage | `AetherMockApplicationTests.java` covering all controllers, engines, and registries | ✅ Verified |

