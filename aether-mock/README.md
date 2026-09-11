# ⚡ AetherMock.ai

> **AI-Native, Multi-Service, Multi-Scenario API Virtualization Engine**  
> Built with Spring Boot 4.1.1, Java 25 (Virtual Threads), Spring AI, and WireMock V3.

---

## 🚀 Overview

**AetherMock.ai** eliminates tedious and brittle manual mock JSON configurations. It dynamically ingests standard `openapi.yml` service contracts alongside human-readable `scenario.md` business logic files, synthesizing dynamic, production-grade WireMock stubs at runtime using Spring AI and WireMock Handlebars templating.

---

## 🛠️ Architecture & Core Components

```
aether-mock/
├── pom.xml
├── README.md
├── REQUIREMENTS.md
└── src/
    ├── main/
    │   ├── java/ai/aethermock/
    │   │   ├── AetherMockApplication.java
    │   │   ├── config/
    │   │   │   └── WireMockConfig.java
    │   │   ├── controller/
    │   │   │   ├── AdminController.java
    │   │   │   └── MultiServiceMockController.java
    │   │   ├── dto/
    │   │   │   ├── ServiceContext.java
    │   │   │   └── WireMockStubSpec.java
    │   │   ├── engine/
    │   │   │   ├── MockEngine.java
    │   │   │   └── MultiServiceRegistry.java
    │   │   └── service/
    │   │       └── SpecIngestionService.java
    │   └── resources/
    │       ├── application.yml
    │       └── mock-data/
    │           ├── payment-service/
    │           │   ├── openapi.yml
    │           │   ├── standard-success.md
    │           │   └── high-value-fraud.md
    │           └── user-service/
    │               ├── openapi.yml
    │               └── onboarding-failure.md
    └── test/java/ai/aethermock/
        └── AetherMockApplicationTests.java
```

- **`MultiServiceRegistry`**: Scans the mock data directory on application startup using **Java 25 Virtual Threads** for non-blocking, parallel directory ingestion.
- **`SpecIngestionService`**: Bridges OpenAPI contracts and scenario requirements using Spring AI `ChatClient` with structured JSON schema output, with an automated fallback synthesis engine.
- **`MockEngine`**: Manages an embedded **WireMockServer** on port `8089`, translating synthesized specs into dynamic stub mappings with Handlebars response templating (e.g., `{{jsonPath request.body '$.transactionId'}}`), and persists mappings to disk.
- **`MultiServiceMockController`**: Ingestion router mapping `/mock/{serviceName}/**` with scenario precedence:
  1. Header override: `X-Aether-Scenario: <scenarioName>`
  2. Active scenario state: `context.activeScenario().get()`
- **`AdminController`**: Administrative control plane for listing services, switching active scenarios dynamically, and reloading mock specs.

---

## 🚦 Getting Started

### Prerequisites
- **JDK 25** (e.g., Amazon Corretto 25 LTS)
- **Maven 3.9+**

### Build & Run
```bash
# Set JAVA_HOME (macOS Corretto 25 example)
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home

# Compile and run tests
mvn clean package

# Start application
mvn spring-boot:run
```

---

## 📡 API Endpoints

### 1. Mock Ingestion Gateway
- **URL:** `POST /mock/{serviceName}/*`
- **Header Override:** `X-Aether-Scenario: <scenarioName>`

**Example: Payment Service (Standard Success)**
```bash
curl -X POST http://localhost:8080/mock/payment-service/v1/payments/process \
  -H "Content-Type: application/json" \
  -d '{"transactionId": "TXN-1001", "amount": 250.00, "currency": "USD"}'
```
*Response (HTTP 200):*
```json
{
  "status": "APPROVED",
  "transactionId": "TXN-1001",
  "approvalCode": "APP-99001"
}
```

**Example: Payment Service (High-Value Fraud Override)**
```bash
curl -X POST http://localhost:8080/mock/payment-service/v1/payments/process \
  -H "Content-Type: application/json" \
  -H "X-Aether-Scenario: high-value-fraud" \
  -d '{"transactionId": "TXN-FRAUD-99", "amount": 50000.00, "currency": "USD"}'
```
*Response (HTTP 422, Header `X-Risk-Level: HIGH`):*
```json
{
  "status": "TRIGGERED_MANUAL_REVIEW",
  "transactionId": "TXN-FRAUD-99",
  "errorCode": "ERR_RISK_THRESHOLD"
}
```

### 2. Admin Control Plane
- `GET /admin/services`: Lists registered services and scenarios.
- `POST /admin/services/{serviceName}/scenario?scenarioName={scenarioName}`: Switches active scenario.
- `POST /admin/reload`: Triggers virtual-thread parallel directory rescan.

---

## 📝 How to Add or Update Your Own Specs & Scenarios

AetherMock.ai is designed to be completely zero-code for mock configuration. You don't write mock servers or JSON stub files manually. You only provide your **OpenAPI contract** and **human-readable Scenario Markdown files**.

### Step 1: Create a Service Directory
Inside `src/main/resources/mock-data/` (or your custom configured `aethermock.data-dir`), create a directory named after your service:
```bash
mkdir -p src/main/resources/mock-data/order-service
```

### Step 2: Add Your OpenAPI Specification (`openapi.yml`)
Drop your standard OpenAPI 3.0+ contract into the directory:
```yaml
# src/main/resources/mock-data/order-service/openapi.yml
openapi: 3.0.3
info:
  title: Order Service
  version: 1.0.0
paths:
  /v1/orders:
    post:
      summary: Place order
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required: [orderId, customerId, total]
              properties:
                orderId: { type: string }
                customerId: { type: string }
                total: { type: number }
      responses:
        '201':
          description: Order Placed Successfully
        '400':
          description: Out of Stock
```

### Step 3: Write Scenario Files (`<scenario-name>.md`)
Create one or more Markdown files in the same folder. Each `.md` file represents a distinct testing scenario.

#### Example Scenario A: Happy Path (`order-success.md`)
```markdown
# Scenario: Order Creation Success

## Target API
- **Endpoint:** POST /v1/orders

## Trigger Condition
- **Condition:** Default standard order with valid inventory.

## Expected Behavior
- **HTTP Status:** 201
- **Headers:** Content-Type: application/json

## Response Body Instructions
- Return JSON: status = "CREATED", mirror orderId and customerId from request, generate fulfillmentId = "FULFILL-001".
```

#### Example Scenario B: Negative Flow / Error (`out-of-stock.md`)
```markdown
# Scenario: Out of Stock Error

## Target API
- **Endpoint:** POST /v1/orders

## Trigger Condition
- **Condition:** Inventory depleted.

## Expected Behavior
- **HTTP Status:** 400
- **Headers:** Content-Type: application/json, X-Error-Code: INVENTORY_DEPLETED

## Response Body Instructions
- Return JSON: status = "FAILED", mirror orderId from request, message = "Requested items out of stock".
```

### Step 4: Hot-Reload Without Restarting the Server
If the application is already running, apply your changes immediately without restarting:
```bash
curl -X POST http://localhost:8080/admin/reload
```
AetherMock will scan the directories in parallel using Java 25 Virtual Threads and register the new service and scenarios.

Verify that your new service is active:
```bash
curl http://localhost:8080/admin/services
```

### Step 5: Test Your New Mock Service
Call the mock endpoint using the service name path prefix:

**Default Active Scenario:**
```bash
curl -X POST http://localhost:8080/mock/order-service/v1/orders \
  -H "Content-Type: application/json" \
  -d '{"orderId": "ORD-5501", "customerId": "CUST-99", "total": 49.99}'
```

**Override with Out-of-Stock Scenario:**
```bash
curl -X POST http://localhost:8080/mock/order-service/v1/orders \
  -H "Content-Type: application/json" \
  -H "X-Aether-Scenario: out-of-stock" \
  -d '{"orderId": "ORD-5501", "customerId": "CUST-99", "total": 49.99}'
```

### Step 6: (Optional) Change the External Mock Data Directory
If you prefer keeping your specs outside the source code tree (e.g., in a shared repo or folder):
In `application.yml` or via environment variable:
```yaml
aethermock:
  data-dir: /path/to/my-team-specs
```
Or run with:
```bash
mvn spring-boot:run -Daethermock.data-dir=/path/to/my-team-specs
```

---

## 🧪 Testing

Execute the test suite:
```bash
mvn test
```
All unit and integration tests run under Java 25 with full coverage of controllers, registries, and dynamic stubs.

