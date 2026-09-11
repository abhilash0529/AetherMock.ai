# Scenario: Fetch User Success

## Target API
- **Endpoint:** GET /v1/users/{userId}

## Trigger Condition
- **Condition:** userId path parameter is present and valid.

## Expected Behavior
- **HTTP Status:** 200
- **Headers:** Content-Type: application/json

## Response Body Instructions
- Return JSON: status = "ACTIVE", mirror userId from request path, generate email = "user@example.com", generate username = "johndoe", createdAt = "{{now format='yyyy-MM-dd\'T\'HH:mm:ss\'Z\''}}".
