# Scenario: Standard Payment Success

## Target API
- **Endpoint:** POST /v1/payments/process

## Trigger Condition
- **Condition:** Amount is less than or equal to 10000.00 and currency is "USD".

## Expected Behavior
- **HTTP Status:** 200
- **Headers:** Content-Type: application/json

## Response Body Instructions
- Return JSON: status = "APPROVED", mirror transactionId from request, generate approvalCode = "APP-99001".

