# Scenario: High Value Fraud Check

## Target API
- **Endpoint:** POST /v1/payments/process

## Trigger Condition
- **Condition:** Amount > 10000.00

## Expected Behavior
- **HTTP Status:** 422
- **Headers:** Content-Type: application/json, X-Risk-Level: HIGH

## Response Body Instructions
- Return JSON: status = "TRIGGERED_MANUAL_REVIEW", mirror transactionId from request, errorCode = "ERR_RISK_THRESHOLD".

