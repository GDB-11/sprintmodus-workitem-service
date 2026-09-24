# sprintmodus-workitem-service

Work items, workflows, audit, comments and the real-time board (port **8093**, reached through the api-gateway at
`/api/work-items/**`, `/api/status-workflows/**`, `/api/admin/status-workflows/**` and `/ws/**`).

**Status: skeleton (Phase 3).** It boots, registers in Eureka, authenticates every `/api/**` request from its JWT and
routes it to the caller's tenant database, exactly like project-service (see its README and the common-lib README), with the same persistence rule: every query is native SQL, repositories in `infrastructure/persistence`.
Entities, use cases and controllers arrive in Phase 5, the WebSocket board in Phase 8 (the WebSocket starter is already
on the classpath).

## Calling project-service

`adapter/feign/client/ProjectServiceClient` (OpenFeign, resolved through Eureka) has stub methods `getProject`,
`getSprint` and `updateSprintVelocity`. Sprints belong to project-service: although both services share a tenant
database, this service never queries the `Sprint` table itself. `BearerTokenRelay` forwards the caller's JWT on every
call, so project-service authenticates the same user and routes to the same tenant. With no authenticated caller nothing
is forwarded and project-service refuses the call.

## Running and tests

Same as project-service: `./mvnw spring-boot:run` (dev profile) and `./mvnw verify` (Docker required).
`TenantRoutingIntegrationTest` covers the tenant routing and rejection paths, and also checks the Feign calls against a
stub HTTP server: the token is relayed, decoding works, and each call carries its own caller's token.
