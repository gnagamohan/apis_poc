# apis_poc

Local stack on Docker Desktop:

| Service        | Image / build                        | Host port            | Networks                          |
|----------------|--------------------------------------|----------------------|-----------------------------------|
| nginx          | `nginx:stable-alpine`                | **80** (only one)    | edge, admin, mail-ui              |
| gateway        | `./gateway` (Spring Cloud Gateway)   | none (internal 8080) | edge, gw-todo, gw-mcp, gw-auth    |
| todo-service   | `./todo-service` (Spring Boot)       | none (internal 8080) | gw-todo, db-todo                  |
| todo-mcp       | `./todo-mcp` (Play 3.0.12, Java)     | none (internal 9000) | gw-mcp                            |
| auth-service   | `./auth-service` (Play 3.0.12, Java) | none (internal 9000) | gw-auth, db-auth, mail-smtp       |
| mailpit        | `axllent/mailpit:v1.32.0` (dev mail) | none (internal 1025 SMTP, 8025 UI) | mail-smtp, mail-ui |
| postgres       | `pgvector/pgvector:pg17`             | none (internal 5432) | db-todo, db-auth, db-admin        |
| pgadmin        | `dpage/pgadmin4`                     | none (internal 80)   | admin, db-admin                   |

**All APIs and MCP tools are reachable only through the gateway.** Each Docker network links exactly two
tiers, and every network except `edge` is `internal` (no host or internet access). The result:

| From \ can reach | gateway | todo-service | todo-mcp | auth-service | postgres | pgadmin | mailpit |
|------------------|:-------:|:------------:|:--------:|:------------:|:--------:|:-------:|:-------:|
| nginx            | ✔       |              |          |              |          | ✔       | ✔ (UI)  |
| gateway          | –       | ✔            | ✔        | ✔            |          |         |         |
| todo-service     | ✔       | –            |          |              | ✔        |         |         |
| todo-mcp         | ✔       |              | –        |              |          |         |         |
| auth-service     | ✔       |              |          | –            | ✔        |         | ✔ (SMTP)|
| pgadmin          |         |              |          |              | ✔        | –       |         |

Docker networks are two-way and don't filter ports, so the "server" side of a link (postgres, mailpit) can
technically open connections back. Neither initiates any calls.

So todo-mcp calls the REST API via the gateway, todo-service only ever receives the gateway's verified
`X-User-Email`, and no container can call another service directly to skip the JWT check.

```
localhost:80 ─ nginx ─┬─ /auth/**   → gateway:8080 ─ /auth/login → auth-service:9000 /login → postgres (authdb)
                      ├─ /api/**    → gateway:8080 ─ [JWT] /api/todos/** → todo-service:8080 → postgres (tododb)
                      ├─ /mcp       → gateway:8080 ─ [JWT] /mcp → todo-mcp:9000 ─(REST + same JWT)→ gateway /api/todos
                      ├─ /mailpit/  → mailpit:8025 (dev mail catcher)  ←(SMTP)─ auth-service
                      └─ /pgadmin/  → pgadmin:80 ──────────────────────────────────────────→ postgres
```

## Run

```powershell
# Docker Desktop on Windows: compose's bake builder needs this to read the Dockerfiles
$env:BUILDX_BAKE_ENTITLEMENTS_FS = "0"
docker compose up -d --build
```

(Or set it once permanently with `setx BUILDX_BAKE_ENTITLEMENTS_FS 0` and open a new terminal.)

Stop: `docker compose down` (add `-v` to wipe the database and pgAdmin volumes).

## Authentication

In the browser, **http://localhost/signup** creates an account and **http://localhost/login** logs in and shows
the token with a Copy button. Both pages are static files in `nginx/html` that call the endpoints below.

1. (Once) `POST http://localhost/auth/signup` with `{"email": "...", "password": "..."}` creates an account and
   returns `201 {"id", "email", "createdAt"}`.
   - The email is trimmed and lower-cased; a duplicate (in any letter case) gives `409`.
   - The password must be 8–72 bytes (`400` otherwise) and is stored as a BCrypt hash.
   - Sign-up does not log you in.
2. `POST http://localhost/auth/login` with `{"email": "...", "password": "..."}` returns
   `{"access_token": "<JWT>", "token_type": "Bearer", "expires_in": 600}`. Wrong credentials → `401`.
3. Send `Authorization: Bearer <JWT>` on every `/api/todos/**`, `/mcp` and `/auth/password/change` request.
   The token is valid for **10 minutes**; after that, log in again.

### Password reset and change

| Endpoint | Auth | Body | Result |
|----------|------|------|--------|
| `POST /auth/password-reset/request` | public | `{"email"}` | always `202` (same response whether or not the account exists). If it exists, emails a one-time token |
| `POST /auth/password-reset/confirm` | public | `{"token", "newPassword"}` | `204`; `400` if the token is invalid, expired or already used, or the password is too weak |
| `POST /auth/password/change` | **JWT** | `{"currentPassword", "newPassword"}` | `204`; `403` wrong current password; `400` weak or unchanged password |

- **Reset tokens:** 32 random bytes, valid 15 minutes, single use. Only their SHA-256 is stored
  (`authdb.password_reset_tokens`). A successful reset or change invalidates every other outstanding token
  for the user.
- **Emails:** a "password was changed" email is sent after every reset or change. All emails go over SMTP to
  **Mailpit**, a dev mail catcher, so nothing leaves your machine. Read them at `http://localhost/mailpit/`.
  Its API (`/mailpit/api/v1/...`) is what the Postman tests use.
- **Background sending:** the reset email is sent in the background, so `request` answers immediately either way.
- **Existing tokens keep working:** JWTs issued before a password change stay valid until they expire (≤ 10
  minutes). They are stateless, and the gateway doesn't look them up.

- **auth-service** (Play, Java) checks the password against BCrypt hashes in `authdb.users` and signs an
  HS256 JWT with `JWT_SECRET`. Claims: `iss=auth-service`, `sub` (user id), `email`, `iat`, `exp` (+10 min), `jti`.
  On startup it creates the user `AUTH_SEED_USER_EMAIL` / `AUTH_SEED_USER_PASSWORD` from `.env` if missing.
- **gateway** rejects requests without a valid token with `401`: bad signature, expired, wrong issuer, or no
  `email` claim. Otherwise it forwards the `email` claim to downstream services as `X-User-Email`. Any
  `X-User-Email` sent by the client is removed first, so it can't be spoofed.
- **todo-service** saves each to-do with `owner_email` = `X-User-Email`, and only ever returns, updates or
  deletes the caller's own to-dos (other users' ids give `404`).
- **todo-mcp** forwards the caller's `Authorization` header to the REST API, so MCP tools act as that user.

```powershell
$body = '{"email":"alice@example.com","password":"alice-password"}'
Invoke-RestMethod -Method Post http://localhost/auth/signup -ContentType application/json -Body $body
$token = (Invoke-RestMethod -Method Post http://localhost/auth/login -ContentType application/json -Body $body).access_token
Invoke-RestMethod http://localhost/api/todos -Headers @{ Authorization = "Bearer $token" }
```

Postman: the **auth-service** collection (see [Postman collections](#postman-collections)) covers:
- **Login:** response shape, `no-store`, HS256, `email` claim, `exp - iat = 600`, case-insensitive email.
- **Sign-up:** `201`, then log in as the new user, who has an empty to-do list. Errors: duplicate email
  (also in other letter case) `409`, short or over-72-byte password, invalid email, missing field, invalid JSON.
  Each run creates a new uniquely named user.
- **Using the token:** valid → 200, no token / tampered token → 401, spoofed `X-User-Email` ignored.
- **Login errors.**

## Use

- To-do API: `http://localhost/api/todos` (JWT required)
- pgAdmin: `http://localhost/pgadmin/`. No login (desktop mode). The "apis-poc postgres" server is pre-registered
  and its password is supplied automatically from `POSTGRES_PASSWORD`. nginx is bound to `127.0.0.1` only, so this
  password-free pgAdmin isn't reachable from other machines.
  If you change `pgadmin/servers.json`, reset pgAdmin's config: `docker compose rm -sf pgadmin && docker volume rm apis-poc_pgadmin-data && docker compose up -d`.

### To-do endpoints

| Method | Path                         | Body                                           |
|--------|------------------------------|------------------------------------------------|
| GET    | `/api/todos[?completed=true]`| –                                              |
| GET    | `/api/todos/{id}`            | –                                              |
| POST   | `/api/todos`                 | `{"title":"...","description":"...","completed":false}` |
| PUT    | `/api/todos/{id}`            | same as POST                                   |
| DELETE | `/api/todos/{id}`            | –                                              |

Responses include `ownerEmail`. Errors come back as RFC 7807 problem details (400 validation,
401 missing or invalid token, 404 not found or not yours).

Postman: the **todo-service** collection (see [Postman collections](#postman-collections)).

## MCP server (todo-mcp)

`todo-mcp` is a Play Framework (Java) app that exposes the to-do API as MCP tools over the
**Streamable HTTP** transport at `http://localhost/mcp`. It holds no database code: each tool calls the REST
API through the gateway.

| Tool          | Arguments                                     | REST call(s)                    |
|---------------|-----------------------------------------------|---------------------------------|
| `list_todos`  | `completed?` (boolean)                        | `GET /api/todos[?completed=]`   |
| `get_todo`    | `id`                                          | `GET /api/todos/{id}`           |
| `create_todo` | `title`, `description?`, `completed?`         | `POST /api/todos`               |
| `update_todo` | `id`, any of `title` / `description` / `completed` (partial) | `GET` then `PUT /api/todos/{id}` |
| `delete_todo` | `id`                                          | `DELETE /api/todos/{id}`        |

Implementation notes (`todo-mcp/app`):
- Tools only, stateless (no `Mcp-Session-Id`). Every JSON-RPC request gets one `application/json` response,
  notifications get `202`, and `GET /mcp` returns `405` (no server-initiated SSE stream).
- Protocol versions `2025-11-25`, `2025-06-18` and `2025-03-26` are supported.
- Requests carrying a browser `Origin` other than localhost are rejected with `403` (DNS-rebinding protection).
- API failures (404, 400, API down) come back as tool results with `isError: true`, so the model can react.

Connect a client:

A JWT is required here too. The token expires after 10 minutes, so re-add the header after logging in again:

```powershell
claude mcp add --transport http todo http://localhost/mcp --header "Authorization: Bearer $token"   # Claude Code
npx @modelcontextprotocol/inspector@2.12.0    # Inspector UI: "Streamable HTTP", URL http://localhost/mcp, add the Authorization header
```

Postman: the **todo-mcp** collection (see [Postman collections](#postman-collections)): login, lifecycle, all five tools, error cases.

### Command-line client with browser login

`tools/mcp/todo.py` (Python standard library only) calls the MCP tools. When it has no token, or the token
has expired, it opens the login page and waits for you to log in:

```powershell
python tools/mcp/todo.py call list_todos
python tools/mcp/todo.py call create_todo '{"title": "Swim", "description": "Today"}'
python tools/mcp/todo.py call delete_todo '{"id": 58}'
python tools/mcp/todo.py token     # print a valid token (logs in if needed)
python tools/mcp/todo.py logout    # forget the cached token
```

How the login works (loopback, like `gh auth login`):
1. The script listens once on `127.0.0.1:<random port>` and opens
   `http://localhost/login?callback=http://127.0.0.1:<port>/callback&state=<random>`.
2. You log in on the page, which then POSTs the token and `state` to that callback.
3. The script checks `state` and the `Origin`, caches the token in `~/.apis_poc/token.json` (override with
   `APIS_POC_TOKEN_FILE`) and runs the call. Cached tokens are reused until 30 s before they expire.

The script never sees your password. The page only sends tokens to loopback `http://…:<port>/callback`
addresses. Opened without `callback`, `/login` just shows the token with a Copy button.

## Postman collections

Three collections are generated by `tools/postman/generate.py`. Each one logs in first, stores the JWT in
`accessToken` and sends it as a Bearer token. Credentials are the collection variables `userEmail` /
`userPassword` (default: the seed user). The sign-up and "other users" requests create a new, uniquely named
user on every run; those users are not deleted.

| Collection     | Covers |
|----------------|--------|
| `todo-service` | Login, CRUD flow (create → get → list → update → filter → delete → 404), other users (a freshly signed-up user gets 404 for the main user's to-do and can't change it), errors (400, 404, no token 401) |
| `todo-mcp`     | Login, MCP lifecycle (initialize, initialized, ping, tools/list), all five tools as the logged-in user, protocol and auth errors, other users (tools can't see or delete another user's to-do) |
| `auth-service` | Login (response shape, HS256, `email` claim, 10-minute expiry), sign-up (201, log in as new user, 409 duplicate, validation), using the token (200, no token / tampered 401, spoofed `X-User-Email` ignored), login errors, forgot password (202 for any email, token read from Mailpit, confirm, single use, old/new password), change password (401/403/400 cases, 204, old/new password, notification email) |

Where they live:
- `postman-json/*.postman_collection.json`: Postman v2.1 JSON, for importing or for the command line:
  `npx newman run postman-json/todo-service.postman_collection.json`
- `postman/postman/collections/<name>/`: YAML for the Postman VS Code extension, which syncs it to your Postman
  workspace (`postman/.postman/resources.yaml`). The extension converts JSON files dropped into `postman/` into
  this format and deletes the JSON. That's why the JSON copies live in `postman-json/` instead.

Regenerate after changing the APIs (needs `pip install pyyaml`):

```powershell
python tools/postman/generate.py
```

This rewrites `postman-json/` and the YAML of collections the extension already tracks, in place, keeping
their cloud links. A collection it doesn't track yet is written as JSON into `postman/` for the extension
to import.

## Database

- The init script `postgres/init/01-init.sh` runs **only on the first start with an empty volume**. It enables
  `vector` (pgvector) and creates `tododb` (owner `todo_app`) and `authdb` (owner `auth_app`).
- The `todos` table is created by Flyway (`todo-service/src/main/resources/db/migration`). V2 added `owner_email`;
  to-dos that existed before it were assigned to `TODO_LEGACY_OWNER_EMAIL` (default `demo@example.com`).
- The `users` table is created by Play evolutions (`auth-service/conf/evolutions`).
- For psql without a host port: `docker exec -it postgres psql -U postgres -d tododb`

## Config

All credentials, `JWT_SECRET`, the seed user and the nginx host port live in `.env` (template in `.env.example`).
These are dev defaults; change them before sharing. `JWT_SECRET` is shared by auth-service (signs) and the gateway (verifies).
