package ai.aethermock;

import ai.aethermock.dto.ServiceContext;
import ai.aethermock.dto.WireMockStubSpec;
import ai.aethermock.engine.MultiServiceRegistry;
import ai.aethermock.service.SpecIngestionService;
import ai.aethermock.controller.AdminController;
import ai.aethermock.controller.MultiServiceMockController;
import ai.aethermock.engine.MockEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class AetherMockApplicationTests {

    private MockMvc mockMvc;

    @Autowired
    private MultiServiceRegistry registry;

    @Autowired
    private SpecIngestionService ingestionService;

    @Autowired
    private MockEngine mockEngine;

    @Autowired
    private ai.aethermock.engine.ScenarioMatcher scenarioMatcher;

    @BeforeEach
    void setUp() {
        MultiServiceMockController mockController = new MultiServiceMockController(registry, ingestionService, mockEngine, scenarioMatcher);
        AdminController adminController = new AdminController(registry, mockController);
        mockMvc = MockMvcBuilders.standaloneSetup(adminController, mockController).build();
    }

    @Test
    void contextLoads() {
        assertThat(registry).isNotNull();
        assertThat(ingestionService).isNotNull();
    }

    @Test
    void multiServiceRegistry_LoadsConfiguredServices() {
        assertThat(registry.getAllServices()).containsKey("payment-service");
        assertThat(registry.getAllServices()).containsKey("user-service");

        ServiceContext paymentCtx = registry.getService("payment-service").orElseThrow();
        assertThat(paymentCtx.scenarios()).containsKey("standard-success");
        assertThat(paymentCtx.scenarios()).containsKey("high-value-fraud");
        assertThat(paymentCtx.openApiContent()).contains("Payment Service");

        ServiceContext userCtx = registry.getService("user-service").orElseThrow();
        assertThat(userCtx.scenarios()).containsKey("onboarding-failure");
        assertThat(userCtx.openApiContent()).contains("User Service");
    }

    @Test
    void specIngestionService_SynthesizesStubsAccurately() {
        ServiceContext paymentCtx = registry.getService("payment-service").orElseThrow();

        // Test Standard Success
        String successMd = paymentCtx.scenarios().get("standard-success");
        WireMockStubSpec successSpec = ingestionService.synthesizeStub("payment-service", paymentCtx.openApiContent(), successMd);
        assertThat(successSpec.response().status()).isEqualTo(200);
        assertThat(successSpec.response().body()).contains("APPROVED");
        assertThat(successSpec.response().body()).contains("{{jsonPath request.body '$.transactionId'}}");

        // Test High-Value Fraud
        String fraudMd = paymentCtx.scenarios().get("high-value-fraud");
        WireMockStubSpec fraudSpec = ingestionService.synthesizeStub("payment-service", paymentCtx.openApiContent(), fraudMd);
        assertThat(fraudSpec.response().status()).isEqualTo(422);
        assertThat(fraudSpec.response().body()).contains("TRIGGERED_MANUAL_REVIEW");
        assertThat(fraudSpec.response().headers()).containsEntry("X-Risk-Level", "HIGH");
    }

    @Test
    void adminController_ListServices() throws Exception {
        mockMvc.perform(get("/admin/services").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment-service.serviceName").value("payment-service"))
                .andExpect(jsonPath("$.user-service.serviceName").value("user-service"));
    }

    @Test
    void adminController_SwitchScenarioDynamically() throws Exception {
        mockMvc.perform(post("/admin/services/payment-service/scenario")
                        .param("scenarioName", "high-value-fraud"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UPDATED"))
                .andExpect(jsonPath("$.activeScenario").value("high-value-fraud"));

        // Verify registry updated
        ServiceContext ctx = registry.getService("payment-service").orElseThrow();
        assertThat(ctx.activeScenario().get()).isEqualTo("high-value-fraud");

        // Restore to standard-success
        registry.setActiveScenario("payment-service", "standard-success");
    }

    @Test
    void adminController_Reload() throws Exception {
        mockMvc.perform(post("/admin/reload"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"));
    }

    @Test
    void mockController_StandardSuccessScenario() throws Exception {
        registry.setActiveScenario("payment-service", "standard-success");

        String requestPayload = """
            {
              "transactionId": "TXN-98765",
              "amount": 500.00,
              "currency": "USD"
            }
            """;

        mockMvc.perform(post("/mock/payment-service/v1/payments/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestPayload))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Aether-Scenario", "standard-success"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.transactionId").value("TXN-98765"))
                .andExpect(jsonPath("$.approvalCode").value("APP-99001"));
    }

    @Test
    void mockController_HeaderOverridePrecedence() throws Exception {
        // Active scenario is standard-success, but header requests high-value-fraud
        registry.setActiveScenario("payment-service", "standard-success");

        String requestPayload = """
            {
              "transactionId": "TXN-FRAUD-001",
              "amount": 25000.00,
              "currency": "USD"
            }
            """;

        mockMvc.perform(post("/mock/payment-service/v1/payments/process")
                        .header("X-Aether-Scenario", "high-value-fraud")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestPayload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("X-Aether-Scenario", "high-value-fraud"))
                .andExpect(header().string("X-Risk-Level", "HIGH"))
                .andExpect(jsonPath("$.status").value("TRIGGERED_MANUAL_REVIEW"))
                .andExpect(jsonPath("$.transactionId").value("TXN-FRAUD-001"))
                .andExpect(jsonPath("$.errorCode").value("ERR_RISK_THRESHOLD"));
    }

    @Test
    void mockController_UserServiceOnboardingFailure() throws Exception {
        String requestPayload = """
            {
              "userId": "USR-4091",
              "email": "existing@corp.com",
              "username": "existinguser"
            }
            """;

        mockMvc.perform(post("/mock/user-service/v1/users/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.userId").value("USR-4091"))
                .andExpect(jsonPath("$.errorCode").value("ERR_DUPLICATE_EMAIL"));
    }

    @Test
    void mockController_DynamicTriggerConditionMatchesFraudWithoutHeader() throws Exception {
        // No X-Aether-Scenario header supplied!
        // Payload has high amount (20033333333) and currency CAD
        String requestPayload = """
            {
              "transactionId": "tr-t-10013",
              "amount": 20033333333,
              "currency": "CAD"
            }
            """;

        mockMvc.perform(post("/mock/payment-service/v1/payments/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestPayload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("X-Aether-Scenario", "high-value-fraud"))
                .andExpect(header().string("X-Risk-Level", "HIGH"))
                .andExpect(jsonPath("$.status").value("TRIGGERED_MANUAL_REVIEW"))
                .andExpect(jsonPath("$.transactionId").value("tr-t-10013"))
                .andExpect(jsonPath("$.errorCode").value("ERR_RISK_THRESHOLD"));
    }

    @Test
    void mockController_DynamicTriggerConditionMatchesStandardSuccessWithoutHeader() throws Exception {
        // No X-Aether-Scenario header supplied!
        // Payload has valid standard amount (500) and currency USD
        String requestPayload = """
            {
              "transactionId": "tr-t-10014",
              "amount": 500.00,
              "currency": "USD"
            }
            """;

        mockMvc.perform(post("/mock/payment-service/v1/payments/process")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestPayload))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Aether-Scenario", "standard-success"))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.transactionId").value("tr-t-10014"))
                .andExpect(jsonPath("$.approvalCode").value("APP-99001"));
    }

    @Test
    void mockController_UnknownServiceReturns404() throws Exception {
        mockMvc.perform(post("/mock/non-existent-service/v1/test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }
}
