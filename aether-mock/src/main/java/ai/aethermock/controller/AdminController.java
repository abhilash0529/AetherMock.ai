package ai.aethermock.controller;

import ai.aethermock.dto.ServiceContext;
import ai.aethermock.engine.MultiServiceRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final MultiServiceRegistry registry;
    private final MultiServiceMockController mockController;

    public AdminController(MultiServiceRegistry registry) {
        this(registry, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AdminController(MultiServiceRegistry registry,
            @org.springframework.beans.factory.annotation.Autowired(required = false) MultiServiceMockController mockController) {
        this.registry = registry;
        this.mockController = mockController;
    }

    /**
     * Lists all registered services, available scenarios, and current active state.
     */
    @GetMapping("/services")
    public ResponseEntity<Map<String, ServiceSummary>> listServices() {
        Map<String, ServiceContext> allServices = registry.getAllServices();
        Map<String, ServiceSummary> response = new LinkedHashMap<>();

        allServices.forEach((name, ctx) -> {
            response.put(name, new ServiceSummary(
                    name,
                    ctx.scenarios().keySet(),
                    ctx.activeScenario().get()));
        });

        return ResponseEntity.ok(response);
    }

    /**
     * Dynamically switches the global active scenario for a specific service.
     */
    @PostMapping("/services/{serviceName}/scenario")
    public ResponseEntity<?> setActiveScenario(
            @PathVariable String serviceName,
            @RequestParam String scenarioName) {

        boolean updated = registry.setActiveScenario(serviceName, scenarioName);
        if (updated) {
            return ResponseEntity.ok(Map.of(
                    "service", serviceName,
                    "activeScenario", scenarioName,
                    "status", "UPDATED"));
        } else {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Service or scenario not found",
                    "service", serviceName,
                    "requestedScenario", scenarioName));
        }
    }

    /**
     * Triggers a rescan of the mock data directory in parallel via virtual threads.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        registry.reload();
        if (mockController != null) {
            mockController.clearCache();
        }
        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "message", "Rescanned mock data directory",
                "registeredServicesCount", registry.getAllServices().size()));
    }

    public record ServiceSummary(
            String serviceName,
            Set<String> availableScenarios,
            String activeScenario) {
    }
}
