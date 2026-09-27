# sprintmodus-workitem-service

Work items, workflows, audit and comments (port **8093**, reached through the api-gateway at `/api/work-items/**`,
`/api/status-workflows/**`, `/api/admin/status-workflows/**` and `/ws/**`). The WebSocket board arrives in Phase 8.

Every `/api/**` request is authenticated from its JWT and routed to the caller's tenant database (see the common-lib
README). Errors are `{code, message, timestamp}`.

## Endpoints

Work items are addressed by their UUID `workItemCode`, never by the internal id. `workItemNumber` (per project, from 1000)
and `displayKey` (`WAR-1000`) are for display and search.

| Method | Path | Notes |
|---|---|---|
| GET | `/api/work-items` | Filters: `projectCode`, `sprintCode` or `backlog=true`, `type`, `status`, `parentCode`, `assignee` (user code), `priority`, `q` (free text, matched against title + description with the `ft_work_item_search` full-text index); `sort=board` (a board column's order: priority CRITICAL→LOW, then `boardRank`, unranked last, then number; default is newest number first); `page` (from 0), `size` (1-200, default 50) → `{items, total, page, size}`. Each item carries `assignees`, `childCount` and `boardRank` (`null` = never ranked) |
| POST | `/api/work-items` | `{projectCode, type, title, description?, acceptanceCriteria?, priority?, parentCode?, effortPoints?, estimatedHours?, remainingHours?}` → `201` with the detail. The item starts in its type's first status |
| GET | `/api/work-items/{code}` | Detail: assignees, children, links, `childrenEffortPoints` (sum of active Task children's own effort — never replaces the item's own `effortPoints`), `allowedStatuses` (legal transitions from now) and `warnings` |
| POST / DELETE | `/api/work-items/{code}/links[/{linkCode}]` | `{targetCode, type}` with type `RELATED_TO\|BLOCKS\|IS_BLOCKED_BY\|DUPLICATES\|IS_DUPLICATED_BY`. Rejects a self-link (`400`), a duplicate of the same source/target/type (`409 ALREADY_LINKED`), and one that would close a cycle within that type's graph (`409 CYCLIC_LINK`, checked with a recursive reachability query — a BLOCKS cycle would mean nothing could ever start; a RELATED_TO/DUPLICATES cycle is checked the same way for consistency). Items may be linked across projects |
| PUT | `/api/work-items/{code}` | Partial update; a field left out keeps its value, a blank description or acceptance criteria clears it |
| DELETE | `/api/work-items/{code}` | Soft delete. Its creator or an admin/owner; `409 WORK_ITEM_HAS_CHILDREN` while it has active children |
| PUT / DELETE | `/api/work-items/{code}/parent` | `{parentCode}` sets, `DELETE` removes. Non-recommended parent types succeed with a `NON_STANDARD_HIERARCHY` warning; a different project, a missing parent or a cycle is `400` |
| POST | `/api/work-items/{code}/status` | `{status}`; only transitions of the type's workflow (`409 INVALID_TRANSITION` otherwise, also when someone else moved the item first); a transition with a `requiredRole` needs the actor assigned to the item in that role, or an owner/admin (`403 FORBIDDEN` otherwise) |
| PUT / DELETE | `/api/work-items/{code}/sprint` | Owners/admins only (`403 FORBIDDEN` otherwise). `{sprintCode}` moves, `DELETE` returns it to the backlog. The velocity of both sprints is recomputed |
| PUT | `/api/work-items/{code}/rank` | Owners/admins only (`403`). `{beforeCode}`: puts the card right before that card, or last when `null`, among the active items of its project that share its type, status and priority (`400` if `beforeCode` is not one of them). The group is locked and renumbered `1..n`; audited as `FIELD_CHANGED` of `BoardRank`; broadcasts `ITEMS_REORDERED` |
| POST / DELETE | `/api/work-items/{code}/assignments[/{assignmentCode}]` | `{userCode, role}` with role `DEV|QA|PO|PM|SCRUM_MASTER` |
| GET / POST | `/api/work-items/{code}/comments` | Comments are listed oldest first; there is no edit or delete. The text may mention people as `@[Full Name](userCode)`: a user who is not active in the tenant is `400 UNKNOWN_MENTION`, more than 20 distinct mentions `400 INVALID_COMMENT` (nothing is written). Each mentioned user other than the author gets one `MENTIONED` notification, written in the comment's transaction. The `COMMENTED` audit preview reads `@Full Name` |
| GET | `/api/notifications` | The caller's own notifications, newest first: `?unread=false&page=0&size=20` (size 1–100) → `{items:[{notificationCode, type, workItem:{workItemCode, displayKey, title}, commentCode, actor:{userCode, fullName}, isRead, createdAt, readAt}], total, page, size}`. `type` is a machine code (`MENTIONED`): no text is stored or returned. Notifications about soft-deleted items are not listed |
| GET | `/api/notifications/unread-count` | `{count}`: the number on the bell (the frontend polls it every 60 s) |
| POST | `/api/notifications/{code}/read`, `/api/notifications/read-all` | `204`; idempotent. Someone else's (or unknown) notification is `404` |
| GET | `/api/users?q=&limit=` | Active users of the tenant whose name, or any word of it, starts with `q` (limit 1–50, default 10) → `[{userCode, fullName}]`: the `@` autocomplete. Never an e-mail |
| GET | `/api/work-items/{code}/history` | `?page=0&size=50` (size 1–200). The item's audit trail, **newest change first**: `{items:[{changeType, field, oldValue, newValue, additionalData, changedBy:{userCode,fullName}, createdAt}], total, page, size}`. `oldValue`/`newValue` are what was stored (a status change stores status codes; text is cut at 100 chars); `additionalData` carries the names a client shows (`from`/`to` status names, `from`/`to` parent keys, `targetKey` + `type` of a link, `fullName` + `role` of an assignee, `sprint` = the target sprint's name). No internal ids. A read: it writes no audit entry. Any member reads an active item's history; a soft-deleted item's history (which ends with its `DELETED` entry) is for owners/admins, a `404` for members |
| GET | `/api/status-workflows/{itemType}` | Active statuses (`code`, `displayName`, `order`, `isTerminal`) and transitions (`from`, `to`, `allowedBackward`, `requiredRole`): drives dropdowns and Kanban columns |
| POST | `/api/admin/status-workflows` | Owners/admins. Replaces a type's whole workflow. A status still used by an active item cannot be dropped (`409 STATUS_IN_USE`); dropped, unused statuses are retired, not deleted. A transition may set `requiredRole` (one of `DEV`/`QA`/`PO`/`PM`/`SCRUM_MASTER`) to restrict who may make that move; omitted or `null` means anyone may |

A status in any response is `{code, displayName, isInitial, isTerminal}`: what it *is*, never how it looks. There are no colors or
icons in the database or the API; each client picks its own per theme, with WCAG contrast in mind.

Warnings never block: a response's `warnings` says what to tell the user (`NON_STANDARD_HIERARCHY`, or
`VELOCITY_NOT_UPDATED` when project-service could not be reached after the change was committed).

## Rules worth knowing

- **Every mutation is audited by its use case, in the same transaction** (`WorkItemAudit`: CREATED, STATE_CHANGED,
  FIELD_CHANGED, DESCRIPTION_EDITED, EFFORT_CHANGED, PARENT_CHANGED, SPRINT_CHANGED, ASSIGNED, UNASSIGNED, LINKED,
  UNLINKED, COMMENTED, DELETED; `COMMENT_EDITED`, `COMMENT_DELETED` and `RESTORED` exist in the schema but nothing produces them yet: there is no comment edit/delete or restore endpoint). If the audit row cannot be written, the change is rolled back (`TransactionRunner` port).
- **Effort rollup (Phase 7) is computed on read, never stored.** `WorkItemResponse.childrenEffortPoints` is summed in
  `Responses.WorkItemResponse.from()` from the children `WorkItemDetails.assemble()` already loads — no extra query, no
  trigger, no scheduled job, nothing to keep in sync. A PBI/Bug's own `effortPoints` is unaffected; the two numbers are
  shown side by side, by design (decided 2026-09-24) — the item's own estimate never silently changes underneath it.
- **Numbers are gapless per project**: taken under a row lock on `WorkItemSequence` in the transaction that inserts the item.
- **Status changes are conditional updates** (`WHERE status = from`): of two concurrent moves from the same status one wins,
  the other gets `409`.
- **Default workflows** are seeded by the tenant migration `V1.8` (Epic/Feature 5 states, PBI/Bug 16, Task 3). Tenant
  databases created before it need that migration applied (the bulk migration script).
- **Role-based transition permissions** (Phase 6, tenant migration `V1.9`, `WorkItemStatusTransition.RequiredRole`): a
  transition may require the actor to be assigned to the item in a given `AssignmentRole`; an organization `OWNER` or
  `ADMIN` always overrides it (`Actor.canAdminister()`). Enforced in `ChangeWorkItemStatusUseCase`, checked against
  `AssignmentRepository.isAssigned()` — the database only stores and validates the enum value.
- **Burndown snapshots.** After every committed change that alters a sprint's remaining hours (hours edited, an item finished or reopened, moved into or out of a sprint, deleted, given another parent) `BurndownRecorder` runs the one-statement `INSERT ... SELECT ... FROM SprintBurndownToday ON DUPLICATE KEY UPDATE` (tenant migration V1.11) in a transaction of its own; a failure is only logged. The last snapshot of a day is that day's value, and the view has ACTIVE sprints only, so planned and closed sprints are never written. project-service starts, closes and serves the burndown (`GET /api/sprints/{code}/burndown`). The plan's `BurndownScheduler` was replaced by this: a daily job needs to know every tenant, and they live in the master database, which this service never reads.
- **Velocity** = effort points of PBIs and Bugs in a terminal status. project-service stores it and owns the sprints: this
  service asks it for the sprint's project and status (`ProjectServiceGateway`, OpenFeign, the caller's JWT relayed by
  `BearerTokenRelay`, 2 s connect / 5 s read timeouts) and pushes the number after the change has committed, never inside
  the transaction. SQL only translates a sprint or project code into its id. Moves refused: closed sprint or another
  project's sprint (`409`), sprint not found (`404`), project-service down (`503`, nothing changed).

## Real-time board (WebSocket)

STOMP over WebSocket, through the gateway's `/ws/**` route: `/ws/tenant/{tenantId}/project/{projectId}/board?token=JWT`. A
browser cannot send a header when opening a WebSocket, so the token travels in the query string (it is never logged) and
`/ws/**` has its own security chain (`WebSocketSecurityConfig`); everything else still needs the `/api/**` bearer token.

- **Handshake** (`BoardHandshakeInterceptor`) refuses: no or invalid token (`401`), a token of another tenant than the URL's
  (`403`), a user that is no longer an active member (`401`), a project that does not exist in the tenant (`404`; `503` if
  project-service is down) and an `Origin` outside `sprintmodus.websocket.allowed-origins` (default `http://localhost:4200`).
  What it established (user, token, the one project) is kept in the session and is never taken from a payload again.
- **Every frame** (`BoardChannelInterceptor`): a connection may subscribe only to `/topic/tenant/{t}/project/{p}` of its own
  board (and its private `/user/queue/errors`), and send only under its own `/app/tenant/{t}/project/{p}/`; anything else
  closes the connection. Its token is verified again on each message, so an expired session cannot keep writing.
- **Client to server** (`/app/tenant/{t}/project/{p}/...`): `status-changed {workItemCode, status}`, `item-moved
  {workItemCode, status, position}`, `comment-added {workItemCode, content}`, `connect`, `disconnect`, `user-typing
  {workItemCode}`. The first three run the same use cases as REST (workflow, roles, audit, velocity) and only then broadcast;
  a work item of another project is answered like a missing one (`WORK_ITEM_NOT_FOUND`).
- **Server to clients** (`/topic/tenant/{t}/project/{p}`), always `{type, data}`: `ITEM_STATUS_CHANGED`, `ITEM_MOVED` (with
  `fromStatus`), `COMMENT_ADDED`, `USER_TYPING` (at most every 2 s per user and item), `USER_CONNECTED` / `USER_DISCONNECTED`
  (with the online list; a user with two tabs is one user). The data reuses the REST response types. A failure is sent only
  to the sender, on `/user/queue/errors`, as `{type: "ERROR", data: {code, message, workItemCode}}`.
- `POST /api/work-items/{code}/status` and `POST .../comments` broadcast to the board too, after the commit. A broadcast that
  fails is logged and never fails the request.
- `PUT /api/work-items/{code}/rank` broadcasts `ITEMS_REORDERED` (`{workItemCode, displayKey, status}`): the new order is not
  in the message, clients reload the column. The `position` of an `item-moved` message is still only passed on, never stored:
  the stored order is `BoardRank` (V1.10), set only through the rank endpoint, and cleared (`NULL` = last of its group) when an
  item's status or priority changes, because it then joins another group.
- **In memory, per instance**: presence and the broker itself, so events reach the clients of this instance only.

## Domain events (in-process seam, no broker)

After a change has **committed**, its use case calls the `DomainEventPublisher` port with a `DomainEvent`:
`WorkItemStatusChanged` (from/to status codes), `WorkItemAssigned`, `WorkItemUnassigned` and `WorkItemCommentAdded` (the
comment's code, not its text). Events hold codes and machine identifiers only; a consumer looks up the rest by `workItemCode`.
Rejected or rolled-back changes publish nothing. `infrastructure/event/SpringDomainEventPublisher` wraps each event in a
`TenantEvent(tenantId, occurredAt, event)` (tenant taken from `TenantContext`, so it works for REST and WebSocket alike) and hands
it to Spring's `ApplicationEventPublisher`: listen with `@EventListener` on `TenantEvent`. Listeners run **synchronously** on the
request thread, inside the tenant context, so slow or remote work must be handed off (`@Async`); a failing listener is logged and
never turns a saved change into an error. There is deliberately **no listener, no RabbitMQ and no notification service yet**:
this is only the seam for a future `notification-service`. The board's WebSocket broadcast is unchanged and does not use it.

## Layout

Clean Architecture: `domain` (plain Java: model, hierarchy/effort/diff rules) ← `application` (use cases returning
`Result<T, E>` with sealed error types, DTOs, ports in `port/persistence` and `port/external`) ← `adapter` (`rest`, `feign`, and `websocket`: the
board's STOMP handlers, gates and broadcaster) and `infrastructure` (Spring configuration, persistence). `ErrorMapper` is the one place error types become HTTP statuses
(exhaustive switches).

**Every query is native SQL** (`@Query(nativeQuery = true)`; JPA only maps rows to `@Entity` models). Repository ports live in
`application/port/persistence`; `Jpa*Repository` implementations, entities and `*Queries` interfaces are in
`infrastructure/persistence`. `ArchitectureRulesTest` enforces this.

## Running and tests

`./mvnw spring-boot:run` (dev profile) and `./mvnw verify` (Docker required).

- Unit tests use in-memory fakes for every port (`support/Fakes`, including `Fakes.Links`). `LinksTest` covers linking,
  self-link/duplicate/cycle rejection and unlinking against the fake.
- `WorkItemsApiIntegrationTest` runs the whole service against `mysql:9.7` with the real tenant migrations, real JWTs and a
  stub project-service over HTTP: numbering under concurrency, hierarchy, transitions (including races), audit atomicity
  (a trigger makes the audit insert fail and the change must roll back), sprint moves and velocity, filters and full-text
  search, links (including the real recursive cycle-detection query), tenant isolation and roles.
- `BoardWebSocketIntegrationTest` opens real STOMP clients over real WebSockets against the same setup: handshake refusals
  (token, tenant, project, origin), presence, status changes and moves over the socket and over REST, comments, typing
  throttling, illegal moves told only to the sender, and tenant isolation (a forged subscription or publish closes the
  connection). `WebSocketSessionManagerTest` and `TypingThrottleTest` cover presence and throttling in isolation.
- `TenantRoutingIntegrationTest` covers tenant routing and the Feign relay.

## Docker

`Dockerfile` (multi-stage: Maven build on `eclipse-temurin:26-jdk` -> `eclipse-temurin:26-jre`, layered jar, non-root uid 10001, exec-form
entrypoint, `HEALTHCHECK` on `/actuator/health/liveness`). Nothing secret or environment-specific is baked in: every setting
comes from the environment at run time (see `application.properties`); orchestration lives in `sprintmodus-infrastructure`.

```bash
export GITHUB_TOKEN=<token with read:packages>
docker build --secret id=github_token,env=GITHUB_TOKEN \
  --build-arg GITHUB_ACTOR=<your github user> -t ghcr.io/gdb-11/sprintmodus-workitem-service:dev .
```

`common-lib` and the `db-*` jars come from GitHub Packages through `.mvn/github-settings.xml` (Central is listed first, so only
`com.sprintmodus` reaches GitHub). The token is a build **secret**, never a build argument or a layer. It needs `read:packages`, and
a valid one is required even when the repositories are private. `LIBS_REPO_URL` can point at any Maven repository (for example a
local static one) to build offline.

CI (`.github/workflows/ci.yml`) runs the tests, then builds and pushes `ghcr.io/gdb-11/sprintmodus-workitem-service` tagged `sha-<commit>` on
`main` and `<version>` on a `v<version>` tag, never `latest`. The tests need Docker themselves, so the image build skips them.

## Mentions and notifications (Phase 14)

`AddCommentUseCase` parses the tokens with the pure `MentionParser` (a malformed token is plain text; the same user twice counts once),
checks the users, then in **one transaction** writes the comment, its `CommentMention` rows, one `Notification` per mentioned user
other than the author, and the audit entry. After the commit it publishes `WorkItemCommentAdded` and, per notification,
`UserMentioned` and `NotificationCreated` (codes only, no text). **`NotificationCreated` is the seam for e-mail**: a later
`@Async @EventListener` of `TenantEvent` looks the notification up by its code and sends it, honouring a per-user preference table;
none of that exists yet (no mail dependency, no SMTP settings). Tenant migration V1.12 holds the tables; see its header for the MySQL
index rule (no index that starts with a foreign-key column and continues with a nullable one).
