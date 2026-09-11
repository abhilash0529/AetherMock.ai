# Scenario: User Registration Success

## Target API
- **Endpoint:** POST /v1/users/register

## Trigger Condition
- **Condition:** Email contains '@' and username is present.

## Expected Behavior
- **HTTP Status:** 201
- **Headers:** Content-Type: application/json

## Response Body Instructions
- Return JSON: status = "SUCCESS", mirror userId from request, mirror email from request, mirror username from request, message = "User successfully registered".
