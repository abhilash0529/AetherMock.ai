# Scenario: Fetch Users by Username Success

## Target API
- **Endpoint:** GET /v1/users/search

## Trigger Condition
- **Condition:** Query parameter 'username' is present.

## Expected Behavior
- **HTTP Status:** 200
- **Headers:** Content-Type: application/json

## Response Body Instructions
- Return JSON array under 'users' key.
- Mirror query parameter: username = "{{request.query.username}}".
