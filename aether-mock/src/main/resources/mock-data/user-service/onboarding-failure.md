# Scenario: User Onboarding Failure

## Target API
- **Endpoint:** POST /v1/users/register

## Trigger Condition
- **Condition:** Email already registered or invalid domain.

## Expected Behavior
- **HTTP Status:** 400
- **Headers:** Content-Type: application/json

## Response Body Instructions
- Return JSON: status = "FAILED", mirror userId from request, errorCode = "ERR_DUPLICATE_EMAIL", message = "Email already exists in system".

