"""Generate the Postman collections for apis_poc.

Writes:
  postman-json/<name>.postman_collection.json   Postman v2.1 JSON for import / `npx newman run` (all collections)
  postman/postman/collections/<name>/            Postman VS Code extension YAML, for collections the extension
                                                 already tracks (listed in postman/.postman/resources.yaml)
  postman/<name>.postman_collection.json         JSON for collections the extension doesn't track yet, so it can
                                                 import them

Usage (from the repo root):  python tools/postman/generate.py
Requires: PyYAML (pip install pyyaml)
"""
import json
import pathlib
import re
import shutil
import sys

import yaml

ROOT = pathlib.Path(__file__).resolve().parents[2]
JSON_DIR = ROOT / "postman-json"
EXT_DIR = ROOT / "postman"
EXT_COLLECTIONS = EXT_DIR / "postman" / "collections"
EXT_RESOURCES = EXT_DIR / ".postman" / "resources.yaml"
SCHEMA = "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"

# ======================================================================================= helpers


def url(path, query=None):
    parts = [p for p in path.split("?")[0].strip("/").split("/") if p]
    u = {"raw": "{{baseUrl}}/" + path.lstrip("/"), "host": ["{{baseUrl}}"], "path": parts}
    if query:
        u["query"] = [{"key": k, "value": v} for k, v in query]
        u["raw"] += "?" + "&".join(f"{k}={v}" for k, v in query)
    return u


def request(name, method, path, tests, body=None, headers=None, prerequest=None, query=None):
    req = {"method": method, "header": [{"key": k, "value": v} for k, v in (headers or [])], "url": url(path, query)}
    if body is not None:
        raw = body if isinstance(body, str) else json.dumps(body, indent=2)
        req["body"] = {"mode": "raw", "raw": raw, "options": {"raw": {"language": "json"}}}
    events = []
    if prerequest:
        events.append({"listen": "prerequest", "script": {"type": "text/javascript", "exec": prerequest}})
    events.append({"listen": "test", "script": {"type": "text/javascript", "exec": tests}})
    return {"name": name, "event": events, "request": req}


def folder(name, items):
    return {"name": name, "item": items}


def collection(name, description, variables, folders, tests=None):
    c = {"info": {"name": name, "description": description, "schema": SCHEMA},
         "variable": [{"key": k, "value": v, "type": "string"} for k, v in variables],
         "item": folders}
    if tests:
        c["event"] = [{"listen": "test", "script": {"type": "text/javascript", "exec": tests}}]
    return c


JSON_CT = ("Content-Type", "application/json")
BEARER = ("Authorization", "Bearer {{accessToken}}")
RESPONSE_TIME = ["pm.test('Response time < 2s', () => pm.expect(pm.response.responseTime).to.be.below(2000));"]
DECODE_JWT = [
    "function jwtPart(token, i) {",
    "  const b64 = token.split('.')[i].replace(/-/g, '+').replace(/_/g, '/');",
    "  return JSON.parse(atob(b64 + '='.repeat((4 - b64.length % 4) % 4)));",
    "}",
]
LOGIN_BODY = '{\n  "email": "{{userEmail}}",\n  "password": "{{userPassword}}"\n}'
USER_VARS = [("userEmail", "demo@example.com"), ("userPassword", "demo-password"), ("accessToken", "")]


OTHER_PASSWORD = "other-password-1"
OTHER_BEARER = ("Authorization", "Bearer {{otherToken}}")
OTHER_VARS = [("otherEmail", ""), ("otherToken", "")]


def other_user_requests():
    """Signs up a brand-new user (unique email per run) and logs in: stores otherEmail / otherToken."""
    return [
        request("Sign up another user", "POST", "/auth/signup", [
            "pm.test('201 Created', () => pm.response.to.have.status(201));",
            "pm.collectionVariables.set('otherEmail', pm.response.json().email);",
        ], body='{\n  "email": "other.{{$timestamp}}{{$randomInt}}@example.com",\n  "password": "%s"\n}' % OTHER_PASSWORD,
            headers=[JSON_CT]),
        request("Log in as the other user", "POST", "/auth/login", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "pm.collectionVariables.set('otherToken', pm.response.json().access_token);",
        ], body='{\n  "email": "{{otherEmail}}",\n  "password": "%s"\n}' % OTHER_PASSWORD, headers=[JSON_CT]),
    ]


def login_request(name="Login"):
    """Logs in with userEmail / userPassword and stores the JWT in accessToken."""
    return request(name, "POST", "/auth/login", DECODE_JWT + [
        "pm.test('200 OK', () => pm.response.to.have.status(200));",
        "const body = pm.response.json();",
        "pm.test('Bearer token valid for 10 minutes', () => {",
        "  pm.expect(body.token_type).to.eql('Bearer');",
        "  pm.expect(body.expires_in).to.eql(600);",
        "});",
        "pm.test('JWT carries the email claim', () => pm.expect(jwtPart(body.access_token, 1).email).to.eql(pm.collectionVariables.get('userEmail').toLowerCase()));",
        "pm.collectionVariables.set('accessToken', body.access_token);",
    ], body=LOGIN_BODY, headers=[JSON_CT])


# ======================================================================================= todo-service


def todo_service():
    todo = "/api/todos/{{todoId}}"
    milk = {"title": "Buy milk", "description": "2 litres, semi-skimmed", "completed": False}
    crud = [
        login_request(),
        request("Create todo", "POST", "/api/todos", [
            "pm.test('201 Created', () => pm.response.to.have.status(201));",
            "const body = pm.response.json();",
            "pm.test('Returns the new todo', () => {",
            "  pm.expect(body.id).to.be.a('number');",
            "  pm.expect(body.title).to.eql('Buy milk');",
            "  pm.expect(body.completed).to.eql(false);",
            "  pm.expect(body.ownerEmail).to.eql(pm.collectionVariables.get('userEmail').toLowerCase());",
            "});",
            "pm.test('Location header points to the new todo', () => {",
            "  pm.expect(pm.response.headers.get('Location')).to.match(new RegExp('/api/todos/' + body.id + '$'));",
            "});",
            "pm.collectionVariables.set('todoId', body.id);",
        ], body=milk, headers=[JSON_CT, BEARER]),
        request("Get todo by id", "GET", todo, [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "pm.test('Id matches', () => pm.expect(String(pm.response.json().id)).to.eql(String(pm.collectionVariables.get('todoId'))));",
        ], headers=[BEARER]),
        request("List all todos", "GET", "/api/todos", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "pm.test('Is an array containing the created todo', () => {",
            "  const list = pm.response.json();",
            "  pm.expect(list).to.be.an('array');",
            "  pm.expect(list.map(t => String(t.id))).to.include(String(pm.collectionVariables.get('todoId')));",
            "});",
            "pm.test('Only my todos', () => pm.response.json().forEach(t => pm.expect(t.ownerEmail).to.eql(pm.collectionVariables.get('userEmail').toLowerCase())));",
        ], headers=[BEARER]),
        request("Update todo (mark completed)", "PUT", todo, [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "pm.test('Is completed', () => pm.expect(pm.response.json().completed).to.eql(true));",
        ], body={**milk, "completed": True}, headers=[JSON_CT, BEARER]),
        request("List completed todos", "GET", "/api/todos", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "pm.test('Only completed todos', () => pm.response.json().forEach(t => pm.expect(t.completed).to.eql(true)));",
        ], headers=[BEARER], query=[("completed", "true")]),
        request("List open todos", "GET", "/api/todos", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "pm.test('Only open todos', () => pm.response.json().forEach(t => pm.expect(t.completed).to.eql(false)));",
        ], headers=[BEARER], query=[("completed", "false")]),
        request("Delete todo", "DELETE", todo, [
            "pm.test('204 No Content', () => pm.response.to.have.status(204));",
        ], headers=[BEARER]),
        request("Get deleted todo (404)", "GET", todo, [
            "pm.test('404 Not Found', () => pm.response.to.have.status(404));",
            "pm.test('Problem detail body', () => pm.expect(pm.response.json().title).to.eql('Not Found'));",
        ], headers=[BEARER]),
    ]
    iso = "/api/todos/{{isoTodoId}}"
    isolation = [
        login_request("Login (main user)"),
        request("Create todo as main user", "POST", "/api/todos", [
            "pm.test('201 Created', () => pm.response.to.have.status(201));",
            "pm.collectionVariables.set('isoTodoId', pm.response.json().id);",
        ], body={"title": "Private to the main user"}, headers=[JSON_CT, BEARER]),
        *other_user_requests(),
        request("Other user: get main user's todo (404)", "GET", iso, [
            "pm.test('404 Not Found', () => pm.response.to.have.status(404));",
        ], headers=[OTHER_BEARER]),
        request("Other user: update main user's todo (404)", "PUT", iso, [
            "pm.test('404 Not Found', () => pm.response.to.have.status(404));",
        ], body={"title": "hijacked", "completed": True}, headers=[JSON_CT, OTHER_BEARER]),
        request("Other user: delete main user's todo (404)", "DELETE", iso, [
            "pm.test('404 Not Found', () => pm.response.to.have.status(404));",
        ], headers=[OTHER_BEARER]),
        request("Other user: list shows only their own todos", "GET", "/api/todos", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const list = pm.response.json();",
            "pm.test('Main user\\'s todo is not listed', () => pm.expect(list.map(t => String(t.id))).to.not.include(String(pm.collectionVariables.get('isoTodoId'))));",
            "pm.test('Every item belongs to the other user', () => list.forEach(t => pm.expect(t.ownerEmail).to.eql(pm.collectionVariables.get('otherEmail'))));",
        ], headers=[OTHER_BEARER]),
        request("Main user: todo is unchanged", "GET", iso, [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "pm.test('Not modified by the other user', () => { const t = pm.response.json(); pm.expect(t.title).to.eql('Private to the main user'); pm.expect(t.completed).to.eql(false); });",
        ], headers=[BEARER]),
        request("Main user: clean up", "DELETE", iso, [
            "pm.test('204 No Content', () => pm.response.to.have.status(204));",
        ], headers=[BEARER]),
    ]

    errors = [
        request("Create todo - blank title (400)", "POST", "/api/todos", [
            "pm.test('400 Bad Request', () => pm.response.to.have.status(400));",
            "pm.test('Problem detail body', () => pm.expect(pm.response.json().status).to.eql(400));",
        ], body={"title": ""}, headers=[JSON_CT, BEARER]),
        request("Update missing todo (404)", "PUT", "/api/todos/999999", [
            "pm.test('404 Not Found', () => pm.response.to.have.status(404));",
        ], body={"title": "Does not exist"}, headers=[JSON_CT, BEARER]),
        request("Delete missing todo (404)", "DELETE", "/api/todos/999999", [
            "pm.test('404 Not Found', () => pm.response.to.have.status(404));",
        ], headers=[BEARER]),
        request("List todos without token (401)", "GET", "/api/todos", [
            "pm.test('401 Unauthorized', () => pm.response.to.have.status(401));",
            "pm.test('WWW-Authenticate: Bearer', () => pm.expect(pm.response.headers.get('WWW-Authenticate')).to.match(/^Bearer/));",
        ]),
    ]
    return collection(
        "todo-service",
        "To-do REST API: nginx -> Spring Cloud Gateway (JWT required) -> todo-service.\n\n"
        "Run **CRUD flow** top to bottom (or the whole collection with the Collection Runner). **Login** signs in "
        "with `userEmail` / `userPassword` and stores the JWT in `accessToken`; every request sends it as a Bearer "
        "token. Tokens expire after 10 minutes: re-run **Login** when requests start returning 401. **Create todo** "
        "stores the new id in `todoId` for the later requests. To-dos are saved with, and limited to, the "
        "logged-in user's email (`ownerEmail`).\n\n"
        "**Other users** signs up a fresh user (unique email per run; not deleted) and checks they get 404 for "
        "the main user's to-do and can't change it.",
        [("baseUrl", "http://localhost"), ("todoId", "1"), ("isoTodoId", "")] + USER_VARS + OTHER_VARS,
        [folder("CRUD flow", crud), folder("Other users", isolation), folder("Errors", errors)],
        tests=RESPONSE_TIME,
    )


# ======================================================================================= todo-mcp


def todo_mcp():
    headers = [JSON_CT, ("Accept", "application/json, text/event-stream"), BEARER,
               ("MCP-Protocol-Version", "{{protocolVersion}}")]

    def rpc(name, body, tests, hdrs=None, method="POST"):
        return request(name, method, "/mcp", tests, body=body, headers=headers if hdrs is None else hdrs)

    def call(id_, tool, args):
        return {"jsonrpc": "2.0", "id": id_, "method": "tools/call", "params": {"name": tool, "arguments": args}}

    def call_with_id(id_, tool, extra=""):
        return ('{\n  "jsonrpc": "2.0",\n  "id": %d,\n  "method": "tools/call",\n'
                '  "params": { "name": "%s", "arguments": { "id": {{mcpTodoId}}%s } }\n}') % (id_, tool, extra)

    ok = [
        "pm.test('200 OK', () => pm.response.to.have.status(200));",
        "const res = pm.response.json();",
        "pm.test('JSON-RPC result', () => { pm.expect(res.jsonrpc).to.eql('2.0'); pm.expect(res).to.have.property('result'); });",
    ]
    tool_ok = ok + [
        "const r = res.result;",
        "pm.test('Tool succeeded', () => pm.expect(r.isError).to.eql(false));",
        "pm.test('Text content mirrors structuredContent', () => pm.expect(JSON.parse(r.content[0].text)).to.eql(r.structuredContent));",
    ]
    me = "pm.collectionVariables.get('userEmail').toLowerCase()"

    lifecycle = [
        login_request("login (get JWT)"),
        rpc("initialize", {"jsonrpc": "2.0", "id": 1, "method": "initialize",
                           "params": {"protocolVersion": "{{protocolVersion}}", "capabilities": {},
                                      "clientInfo": {"name": "postman", "version": "1.0.0"}}},
            ok + [
                "pm.test('Negotiates protocol version', () => pm.expect(res.result.protocolVersion).to.eql(pm.collectionVariables.get('protocolVersion')));",
                "pm.test('Advertises tools capability', () => pm.expect(res.result.capabilities).to.have.property('tools'));",
                "pm.test('Server info', () => pm.expect(res.result.serverInfo.name).to.eql('todo-mcp'));",
            ], hdrs=headers[:3]),
        rpc("notifications/initialized", {"jsonrpc": "2.0", "method": "notifications/initialized"},
            ["pm.test('202 Accepted, no body', () => { pm.response.to.have.status(202); pm.expect(pm.response.text()).to.eql(''); });"]),
        rpc("ping", {"jsonrpc": "2.0", "id": 2, "method": "ping"},
            ok + ["pm.test('Empty result', () => pm.expect(res.result).to.eql({}));"]),
        rpc("tools/list", {"jsonrpc": "2.0", "id": 3, "method": "tools/list"}, ok + [
            "const names = res.result.tools.map(t => t.name);",
            "pm.test('Exposes the 5 todo tools', () => pm.expect(names).to.have.members(['list_todos','get_todo','create_todo','update_todo','delete_todo']));",
            "pm.test('Every tool has a description and object inputSchema', () => res.result.tools.forEach(t => {",
            "  pm.expect(t.description).to.be.a('string').and.not.empty;",
            "  pm.expect(t.inputSchema.type).to.eql('object');",
            "}));",
            "pm.test('delete_todo is marked destructive', () => pm.expect(res.result.tools.find(t => t.name === 'delete_todo').annotations.destructiveHint).to.eql(true));",
        ]),
    ]
    flow = [
        rpc("create_todo", call(10, "create_todo", {"title": "Try the MCP server", "description": "from Postman"}),
            tool_ok + [
                "pm.test('Created with id, owned by me', () => {",
                "  pm.expect(r.structuredContent.id).to.be.a('number');",
                "  pm.expect(r.structuredContent.completed).to.eql(false);",
                f"  pm.expect(r.structuredContent.ownerEmail).to.eql({me});",
                "});",
                "pm.collectionVariables.set('mcpTodoId', r.structuredContent.id);",
            ]),
        rpc("get_todo", call_with_id(11, "get_todo"), tool_ok + [
            "pm.test('Same todo', () => { pm.expect(r.structuredContent.id).to.eql(Number(pm.collectionVariables.get('mcpTodoId'))); pm.expect(r.structuredContent.title).to.eql('Try the MCP server'); });",
        ]),
        rpc("update_todo (partial: completed only)", call_with_id(12, "update_todo", ', "completed": true'), tool_ok + [
            "pm.test('Completed, other fields kept', () => { const t = r.structuredContent; pm.expect(t.completed).to.eql(true); pm.expect(t.title).to.eql('Try the MCP server'); pm.expect(t.description).to.eql('from Postman'); });",
        ]),
        rpc("list_todos (completed=true)", call(13, "list_todos", {"completed": True}), tool_ok + [
            "pm.test('Only my completed todos, includes ours', () => {",
            "  const d = r.structuredContent;",
            "  pm.expect(d.count).to.eql(d.todos.length);",
            "  d.todos.forEach(t => { pm.expect(t.completed).to.eql(true); pm.expect(t.ownerEmail).to.eql(" + me + "); });",
            "  pm.expect(d.todos.map(t => t.id)).to.include(Number(pm.collectionVariables.get('mcpTodoId')));",
            "});",
        ]),
        rpc("delete_todo", call_with_id(14, "delete_todo"), tool_ok + [
            "pm.test('Deleted', () => pm.expect(r.structuredContent.deleted).to.eql(true));",
        ]),
        rpc("get_todo (deleted -> isError)", call_with_id(15, "get_todo"), ok + [
            "pm.test('Tool error: not found', () => { pm.expect(res.result.isError).to.eql(true); pm.expect(res.result.content[0].text).to.include('not found'); });",
        ]),
    ]
    other_headers = [h if h is not BEARER else OTHER_BEARER for h in headers]
    iso_call = lambda id_, tool: ('{\n  "jsonrpc": "2.0",\n  "id": %d,\n  "method": "tools/call",\n'
                                  '  "params": { "name": "%s", "arguments": { "id": {{mcpIsoId}} } }\n}') % (id_, tool)
    isolation = [
        login_request("login (main user)"),
        rpc("main user: create_todo", call(30, "create_todo", {"title": "Private to the main user"}), tool_ok + [
            "pm.collectionVariables.set('mcpIsoId', r.structuredContent.id);",
        ]),
        *other_user_requests(),
        rpc("other user: get_todo of main user's id -> isError", iso_call(31, "get_todo"), ok + [
            "pm.test('Not found for the other user', () => { pm.expect(res.result.isError).to.eql(true); pm.expect(res.result.content[0].text).to.include('not found'); });",
        ], hdrs=other_headers),
        rpc("other user: delete_todo of main user's id -> isError", iso_call(32, "delete_todo"), ok + [
            "pm.test('Not found for the other user', () => pm.expect(res.result.isError).to.eql(true));",
        ], hdrs=other_headers),
        rpc("other user: list_todos shows only their own", call(33, "list_todos", {}), tool_ok + [
            "pm.test('Main user\\'s todo is not listed', () => pm.expect(r.structuredContent.todos.map(t => t.id)).to.not.include(Number(pm.collectionVariables.get('mcpIsoId'))));",
            "pm.test('Every item belongs to the other user', () => r.structuredContent.todos.forEach(t => pm.expect(t.ownerEmail).to.eql(pm.collectionVariables.get('otherEmail'))));",
        ], hdrs=other_headers),
        rpc("main user: delete_todo (clean up)", iso_call(34, "delete_todo"), tool_ok + [
            "pm.test('Deleted by its owner', () => pm.expect(r.structuredContent.deleted).to.eql(true));",
        ]),
    ]

    errors = [
        rpc("no token -> 401 (gateway)", {"jsonrpc": "2.0", "id": 19, "method": "tools/list"},
            ["pm.test('401 Unauthorized', () => pm.response.to.have.status(401));"], hdrs=headers[:2]),
        rpc("create_todo blank title -> isError", call(20, "create_todo", {"title": "  "}), ok + [
            "pm.test('Tool error mentions title', () => { pm.expect(res.result.isError).to.eql(true); pm.expect(res.result.content[0].text).to.include('title'); });",
        ]),
        rpc("update_todo without fields -> isError", call(21, "update_todo", {"id": 1}), ok + [
            "pm.test('Tool error', () => pm.expect(res.result.isError).to.eql(true));",
        ]),
        rpc("unknown tool -> -32602", call(22, "no_such_tool", {}), [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "pm.test('Invalid params error', () => pm.expect(pm.response.json().error.code).to.eql(-32602));",
        ]),
        rpc("unknown method -> -32601", {"jsonrpc": "2.0", "id": 23, "method": "resources/list"}, [
            "pm.test('Method not found', () => pm.expect(pm.response.json().error.code).to.eql(-32601));",
        ]),
        rpc("invalid JSON -> 400 - -32700", "{ this is not json", [
            "pm.test('400', () => pm.response.to.have.status(400));",
            "pm.test('Parse error', () => pm.expect(pm.response.json().error.code).to.eql(-32700));",
        ]),
        rpc("foreign Origin -> 403", {"jsonrpc": "2.0", "id": 24, "method": "ping"},
            ["pm.test('403 Forbidden', () => pm.response.to.have.status(403));"],
            hdrs=headers + [("Origin", "http://evil.example")]),
        rpc("unsupported MCP-Protocol-Version -> 400", {"jsonrpc": "2.0", "id": 25, "method": "ping"},
            ["pm.test('400', () => pm.response.to.have.status(400));"],
            hdrs=headers[:3] + [("MCP-Protocol-Version", "1999-01-01")]),
        rpc("GET /mcp -> 405 (no SSE stream)", None, [
            "pm.test('405', () => pm.response.to.have.status(405));",
            "pm.test('Allow: POST', () => pm.expect(pm.response.headers.get('Allow')).to.eql('POST'));",
        ], hdrs=[("Accept", "text/event-stream"), BEARER], method="GET"),
    ]
    return collection(
        "todo-mcp",
        "MCP (Streamable HTTP) checks for the Play todo-mcp server: nginx /mcp -> gateway (JWT required) -> "
        "todo-mcp -> gateway /api/todos -> todo-service.\n\n"
        "Run top to bottom (Collection Runner or `npx newman run`). **login** gets a JWT (valid 10 minutes) into "
        "`accessToken`; every MCP request sends it as a Bearer token, and todo-mcp forwards it to the REST API, so "
        "the tools act as that user. **create_todo** stores the new id in `mcpTodoId`.\n\n"
        "**4. Other users** signs up a fresh user (unique email per run; not deleted) and checks the MCP tools "
        "can't see or delete the main user's to-do with that user's token.",
        [("baseUrl", "http://localhost"), ("protocolVersion", "2025-11-25"), ("mcpTodoId", "1"), ("mcpIsoId", "")]
        + USER_VARS + OTHER_VARS,
        [folder("1. Lifecycle", lifecycle), folder("2. Tools flow", flow), folder("3. Errors", errors),
         folder("4. Other users", isolation)],
    )


# ======================================================================================= auth-service


def auth_service():
    def login(name, body, tests, prerequest=None):
        return request(name, "POST", "/auth/login", tests, body=body, headers=[JSON_CT], prerequest=prerequest)

    def problem(status, title, detail_part):
        return [
            f"pm.test('{status} {title}', () => pm.response.to.have.status({status}));",
            "pm.test('RFC 7807 problem body', () => {",
            "  pm.expect(pm.response.headers.get('Content-Type')).to.include('application/problem+json');",
            "  const p = pm.response.json();",
            f"  pm.expect(p.status).to.eql({status});",
            f"  pm.expect(p.title).to.eql('{title}');",
            f"  pm.expect(p.detail).to.include('{detail_part}');",
            "});",
            "pm.test('No token returned', () => pm.expect(pm.response.json()).to.not.have.property('access_token'));",
        ]

    me = "pm.collectionVariables.get('userEmail').toLowerCase()"
    login_folder = [
        login("Login - success", LOGIN_BODY, DECODE_JWT + [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const body = pm.response.json();",
            "pm.test('Token response shape', () => {",
            "  pm.expect(body.access_token).to.be.a('string');",
            "  pm.expect(body.access_token.split('.')).to.have.lengthOf(3);",
            "  pm.expect(body.token_type).to.eql('Bearer');",
            "  pm.expect(body.expires_in).to.eql(600);",
            "});",
            "pm.test('Cache-Control: no-store', () => pm.expect(pm.response.headers.get('Cache-Control')).to.include('no-store'));",
            "const header = jwtPart(body.access_token, 0);",
            "const claims = jwtPart(body.access_token, 1);",
            "pm.test('Signed with HS256', () => pm.expect(header.alg).to.eql('HS256'));",
            f"pm.test('email claim is the logged-in user', () => pm.expect(claims.email).to.eql({me}));",
            "pm.test('Issuer and subject', () => { pm.expect(claims.iss).to.eql('auth-service'); pm.expect(claims.sub).to.be.a('string').and.not.empty; });",
            "pm.test('Valid for exactly 10 minutes', () => pm.expect(claims.exp - claims.iat).to.eql(600));",
            "pm.test('Not expired, issued just now', () => {",
            "  const now = Math.floor(Date.now() / 1000);",
            "  pm.expect(claims.exp).to.be.above(now);",
            "  pm.expect(Math.abs(now - claims.iat)).to.be.below(120);",
            "});",
            "pm.test('Has a unique token id (jti)', () => pm.expect(claims.jti).to.be.a('string').and.not.empty);",
            "pm.collectionVariables.set('accessToken', body.access_token);",
        ]),
        login("Login - email is case-insensitive",
              '{\n  "email": "  {{userEmailUpper}}  ",\n  "password": "{{userPassword}}"\n}', DECODE_JWT + [
                  "pm.test('200 OK', () => pm.response.to.have.status(200));",
                  f"pm.test('email claim is normalised to lower case', () => pm.expect(jwtPart(pm.response.json().access_token, 1).email).to.eql({me}));",
              ], prerequest=["pm.collectionVariables.set('userEmailUpper', pm.collectionVariables.get('userEmail').toUpperCase());"]),
    ]
    def signup(name, body, tests, prerequest=None):
        return request(name, "POST", "/auth/signup", tests, body=body, headers=[JSON_CT], prerequest=prerequest)

    signup_body = '{\n  "email": "{{signupEmail}}",\n  "password": "{{signupPassword}}"\n}'
    signup_folder = [
        signup("Sign up - success", signup_body, [
            "pm.test('201 Created', () => pm.response.to.have.status(201));",
            "const u = pm.response.json();",
            "pm.test('Returns the new user', () => {",
            "  pm.expect(u.id).to.be.a('number');",
            "  pm.expect(u.email).to.eql(pm.collectionVariables.get('signupEmail').toLowerCase());",
            "  pm.expect(new Date(u.createdAt).toString()).to.not.eql('Invalid Date');",
            "});",
            "pm.test('Never returns the password or its hash', () => {",
            "  pm.expect(u).to.not.have.property('password');",
            "  pm.expect(u).to.not.have.property('passwordHash');",
            "  pm.expect(u).to.not.have.property('password_hash');",
            "});",
            "pm.test('Cache-Control: no-store', () => pm.expect(pm.response.headers.get('Cache-Control')).to.include('no-store'));",
        ], prerequest=[
            "// A fresh, unique email on every run (mixed case: it is stored lower-cased)",
            "pm.collectionVariables.set('signupEmail', `New.User.${Date.now()}@Example.com`);",
            "pm.collectionVariables.set('signupPassword', 'signup-password-1');",
        ]),
        login("Log in as the new user", signup_body, DECODE_JWT + [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const body = pm.response.json();",
            "pm.test('email claim is the new user', () => pm.expect(jwtPart(body.access_token, 1).email).to.eql(pm.collectionVariables.get('signupEmail').toLowerCase()));",
            "pm.collectionVariables.set('signupToken', body.access_token);",
        ]),
        request("New user starts with no todos", "GET", "/api/todos", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "pm.test('Empty list', () => pm.expect(pm.response.json()).to.eql([]));",
        ], headers=[("Authorization", "Bearer {{signupToken}}")]),
        signup("Duplicate email -> 409", signup_body, problem(409, "Conflict", "already exists")),
        signup("Duplicate email, other case -> 409",
               '{\n  "email": "{{signupEmailUpper}}",\n  "password": "{{signupPassword}}"\n}',
               problem(409, "Conflict", "already exists"),
               prerequest=["pm.collectionVariables.set('signupEmailUpper', pm.collectionVariables.get('signupEmail').toUpperCase());"]),
        signup("Password too short -> 400", {"email": "short.pw@example.com", "password": "1234567"},
               problem(400, "Bad Request", "at least 8")),
        signup("Password over 72 bytes -> 400", {"email": "long.pw@example.com", "password": "x" * 73},
               problem(400, "Bad Request", "72 bytes")),
        signup("Invalid email -> 400", {"email": "not-an-email", "password": "long-enough-1"},
               problem(400, "Bad Request", "valid email")),
        signup("Missing password -> 400", {"email": "no.pw@example.com"}, problem(400, "Bad Request", "required")),
        signup("Invalid JSON -> 400", "{ not json", problem(400, "Bad Request", "JSON")),
    ]

    # ---- password reset / change -----------------------------------------------------------------
    old_pw, new_pw = "original-pass-1", "brand-new-pass-1"

    def fresh_user(name, var):
        """Signs up a uniquely named user with old_pw and stores its email in `var`."""
        return request(name, "POST", "/auth/signup", [
            "pm.test('201 Created', () => pm.response.to.have.status(201));",
            f"pm.collectionVariables.set('{var}', pm.response.json().email);",
        ], body='{\n  "email": "%s.{{$timestamp}}{{$randomInt}}@example.com",\n  "password": "%s"\n}' % (var, old_pw),
            headers=[JSON_CT])

    def login_as(name, email_var, password, status, store=None):
        tests = [f"pm.test('{status}', () => pm.response.to.have.status({status}));"]
        if store:
            tests.append(f"pm.collectionVariables.set('{store}', pm.response.json().access_token);")
        return request(name, "POST", "/auth/login", tests,
                       body='{\n  "email": "{{%s}}",\n  "password": "%s"\n}' % (email_var, password), headers=[JSON_CT])

    def find_email(name, email_var, subject, store):
        """Searches Mailpit for the email; emails are sent in the background, so retry (Runner/newman) briefly."""
        key = "mailRetries_" + store
        return request(name, "GET", "/mailpit/api/v1/search", [
            f"const want = '{subject}';",
            "const msgs = (pm.response.json().messages || []).filter(m => m.Subject === want);",
            f"const tries = Number(pm.collectionVariables.get('{key}') || 0);",
            "if (msgs.length === 0 && tries < 20) {",
            "  // not there yet: run this request again in 0.5 s (Collection Runner / newman)",
            f"  pm.collectionVariables.set('{key}', tries + 1);",
            "  postman.setNextRequest(pm.info.requestName);",
            "  setTimeout(() => {}, 500);",
            "} else {",
            f"  pm.collectionVariables.unset('{key}');",
            "  pm.test('Email arrived in Mailpit', () => pm.expect(msgs.length).to.be.above(0));",
            f"  if (msgs.length) pm.collectionVariables.set('{store}', msgs[0].ID);",
            "}",
        ], query=[("query", "to:{{%s}}" % email_var)])

    def change(name, body, tests, bearer=True):
        return request(name, "POST", "/auth/password/change", tests, body=body,
                       headers=[JSON_CT, ("Authorization", "Bearer {{changeToken}}")] if bearer else [JSON_CT])

    def confirm(name, token_expr, password, tests):
        return request(name, "POST", "/auth/password-reset/confirm", tests,
                       body='{\n  "token": "%s",\n  "newPassword": "%s"\n}' % (token_expr, password), headers=[JSON_CT])

    same_202 = [
        "pm.test('202 Accepted', () => pm.response.to.have.status(202));",
        "pm.test('Same message whether or not the account exists', () => pm.expect(pm.response.json().message).to.eql('If an account exists for this email, a password reset token has been sent to it.'));",
    ]
    forgot = [
        fresh_user("Sign up a user to reset", "resetEmail"),
        request("Request reset", "POST", "/auth/password-reset/request", same_202,
                body='{\n  "email": "{{resetEmail}}"\n}', headers=[JSON_CT]),
        request("Request reset for unknown email (same 202)", "POST", "/auth/password-reset/request", same_202,
                body={"email": "nobody.at.all@example.com"}, headers=[JSON_CT]),
        request("Request reset - invalid email (400)", "POST", "/auth/password-reset/request",
                problem(400, "Bad Request", "valid email"), body={"email": "not-an-email"}, headers=[JSON_CT]),
        find_email("Find the reset email in Mailpit", "resetEmail", "Reset your apis-poc password", "resetMessageId"),
        request("Read the reset token from the email", "GET", "/mailpit/api/v1/message/{{resetMessageId}}", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            "const text = pm.response.json().Text;",
            "const m = text.match(/Reset token: ([A-Za-z0-9_-]{43})/);",
            "pm.test('Email contains a 43-character token', () => pm.expect(m).to.not.be.null);",
            "pm.test('Email says it expires in 15 minutes', () => pm.expect(text).to.include('expires in 15 minutes'));",
            "if (m) pm.collectionVariables.set('resetToken', m[1]);",
        ]),
        confirm("Confirm - wrong token (400)", "not-a-real-token", new_pw,
                problem(400, "Bad Request", "invalid, expired or already used")),
        confirm("Confirm - new password too short (400)", "{{resetToken}}", "short",
                problem(400, "Bad Request", "at least 8")),
        confirm("Confirm - set the new password", "{{resetToken}}", new_pw, [
            "pm.test('204 No Content', () => pm.response.to.have.status(204));",
        ]),
        confirm("Confirm - token can't be reused (400)", "{{resetToken}}", "yet-another-pass-1",
                problem(400, "Bad Request", "invalid, expired or already used")),
        login_as("Old password no longer works (401)", "resetEmail", old_pw, 401),
        login_as("New password works", "resetEmail", new_pw, 200),
    ]

    change_ok = '{\n  "currentPassword": "%s",\n  "newPassword": "%s"\n}' % (old_pw, new_pw)
    change_folder = [
        fresh_user("Sign up a user to change", "changeEmail"),
        login_as("Log in", "changeEmail", old_pw, 200, store="changeToken"),
        change("Change without token (401)", change_ok, [
            "pm.test('401 Unauthorized', () => pm.response.to.have.status(401));",
        ], bearer=False),
        change("Wrong current password (403)",
               '{\n  "currentPassword": "not-my-password",\n  "newPassword": "%s"\n}' % new_pw,
               problem(403, "Forbidden", "Current password is incorrect")),
        change("New password too short (400)",
               '{\n  "currentPassword": "%s",\n  "newPassword": "short"\n}' % old_pw,
               problem(400, "Bad Request", "at least 8")),
        change("New password same as current (400)",
               '{\n  "currentPassword": "%s",\n  "newPassword": "%s"\n}' % (old_pw, old_pw),
               problem(400, "Bad Request", "different from the current password")),
        change("Change password", change_ok, ["pm.test('204 No Content', () => pm.response.to.have.status(204));"]),
        login_as("Old password no longer works (401)", "changeEmail", old_pw, 401),
        login_as("New password works", "changeEmail", new_pw, 200),
        find_email("Password-changed email in Mailpit", "changeEmail", "Your apis-poc password was changed",
                   "changedMessageId"),
    ]

    use_token = [
        request("Token works on /api/todos", "GET", "/api/todos", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            f"pm.test('Only my todos', () => pm.response.json().forEach(t => pm.expect(t.ownerEmail).to.eql({me})));",
        ], headers=[BEARER]),
        request("No token -> 401", "GET", "/api/todos", [
            "pm.test('401 Unauthorized', () => pm.response.to.have.status(401));",
            "pm.test('WWW-Authenticate: Bearer', () => pm.expect(pm.response.headers.get('WWW-Authenticate')).to.match(/^Bearer/));",
        ]),
        request("Tampered token -> 401", "GET", "/api/todos", [
            "pm.test('401 Unauthorized', () => pm.response.to.have.status(401));",
            "pm.test('invalid_token error', () => pm.expect(pm.response.headers.get('WWW-Authenticate')).to.include('invalid_token'));",
        ], headers=[("Authorization", "Bearer {{tamperedToken}}")], prerequest=[
            "// Change the email claim but keep the original signature: the gateway must reject it",
            "const [h, p, s] = pm.collectionVariables.get('accessToken').split('.');",
            "const b64 = p.replace(/-/g, '+').replace(/_/g, '/');",
            "const claims = JSON.parse(atob(b64 + '='.repeat((4 - b64.length % 4) % 4)));",
            "claims.email = 'someone.else@example.com';",
            "const forged = btoa(JSON.stringify(claims)).replace(/\\+/g, '-').replace(/\\//g, '_').replace(/=+$/, '');",
            "pm.collectionVariables.set('tamperedToken', [h, forged, s].join('.'));",
        ]),
        request("Spoofed X-User-Email is ignored", "GET", "/api/todos", [
            "pm.test('200 OK', () => pm.response.to.have.status(200));",
            f"pm.test('Still only my todos', () => pm.response.json().forEach(t => pm.expect(t.ownerEmail).to.eql({me})));",
        ], headers=[BEARER, ("X-User-Email", "someone.else@example.com")]),
    ]
    errors = [
        login("Wrong password -> 401", '{\n  "email": "{{userEmail}}",\n  "password": "definitely-wrong"\n}',
              problem(401, "Unauthorized", "Invalid email or password")),
        login("Unknown email -> 401 (same message)", {"email": "nobody@example.com", "password": "whatever"},
              problem(401, "Unauthorized", "Invalid email or password")),
        login("Missing password -> 400", '{\n  "email": "{{userEmail}}"\n}', problem(400, "Bad Request", "required")),
        login("Missing email -> 400", '{\n  "password": "{{userPassword}}"\n}', problem(400, "Bad Request", "required")),
        login("Invalid email -> 400", {"email": "not-an-email", "password": "x"}, problem(400, "Bad Request", "valid email")),
        login("Password over 72 bytes -> 400", {"email": "demo@example.com", "password": "x" * 73},
              problem(400, "Bad Request", "72 bytes")),
        login("Invalid JSON -> 400", "{ not json", problem(400, "Bad Request", "JSON")),
    ]
    return collection(
        "auth-service",
        "auth-service (Play, Java): nginx /auth/** -> gateway (public) -> auth-service.\n\n"
        "- `POST /auth/login` with `{\"email\", \"password\"}` returns `{access_token, token_type: \"Bearer\", "
        "expires_in: 600}`. The JWT is HS256-signed and carries the `email` claim; it is valid for 10 minutes.\n"
        "- `POST /auth/signup` with `{\"email\", \"password\"}` (password 8-72 bytes) returns `201 {id, email, "
        "createdAt}`, or `409` if the email is already registered.\n\n"
        "- `POST /auth/password-reset/request` `{\"email\"}` always returns `202`; if the account exists, a one-time "
        "token (valid 15 minutes) is emailed. `POST /auth/password-reset/confirm` `{\"token\", \"newPassword\"}` "
        "returns `204`.\n"
        "- `POST /auth/password/change` (JWT required) `{\"currentPassword\", \"newPassword\"}` returns `204`.\n\n"
        "Set `userEmail` / `userPassword` (defaults: the seed user from `.env`). **Login - success** stores the token "
        "in `accessToken`. **Using the token** shows how the gateway treats tokens. **Sign up**, **Forgot password** "
        "and **Change password** each create a new, uniquely named user on every run (they are not deleted), so the "
        "seed user's password is never changed. The password folders read emails from Mailpit "
        "(`/mailpit/api/v1/...`) and retry for a few seconds when run in the Collection Runner or newman.",
        USER_VARS + [("baseUrl", "http://localhost"), ("userEmailUpper", ""), ("tamperedToken", ""),
                     ("signupEmail", ""), ("signupPassword", ""), ("signupEmailUpper", ""), ("signupToken", ""),
                     ("resetEmail", ""), ("resetMessageId", ""), ("resetToken", ""),
                     ("changeEmail", ""), ("changeToken", ""), ("changedMessageId", "")],
        [folder("1. Login", login_folder), folder("2. Sign up", signup_folder),
         folder("3. Using the token", use_token), folder("4. Login errors", errors),
         folder("5. Forgot password", forgot), folder("6. Change password", change_folder)],
        tests=RESPONSE_TIME,
    )


# ======================================================================================= writers


class _Literal(str):
    pass


yaml.SafeDumper.add_representer(
    _Literal, lambda d, s: d.represent_scalar("tag:yaml.org,2002:str", s, style="|"))


def _lit(s):
    return _Literal(s) if "\n" in s else s


def _dump_yaml(path, data):
    path.write_text(yaml.safe_dump(data, sort_keys=False, allow_unicode=True, width=10_000),
                    encoding="utf-8", newline="\n")


def _file_name(name):
    """Same sanitising the Postman extension uses for request file names."""
    return re.sub(r'[<>"|?*]', "", name.replace("/", "-").replace(":", "-")).strip()


def _scripts(events, prefix=""):
    out = []
    for e in events or []:
        kind = "afterResponse" if e["listen"] == "test" else "beforeRequest"
        out.append({"type": prefix + kind, "code": _lit("\n".join(e["script"]["exec"])), "language": "text/javascript"})
    return out


def write_extension_yaml(coll, target):
    """Rewrites a Postman-extension collection folder in place (keeps the folder, so its cloud link stays)."""
    target.mkdir(parents=True, exist_ok=True)
    root = {"$kind": "collection", "description": _lit(coll["info"]["description"]),
            "variables": {v["key"]: v["value"] for v in coll["variable"]}}
    if coll.get("event"):
        root["scripts"] = _scripts(coll["event"], prefix="http:")
    (target / ".resources").mkdir(exist_ok=True)
    _dump_yaml(target / ".resources" / "definition.yaml", root)

    wanted_folders = set()
    for fi, f in enumerate(coll["item"], start=1):
        fdir = target / f["name"]
        wanted_folders.add(f["name"])
        (fdir / ".resources").mkdir(parents=True, exist_ok=True)
        _dump_yaml(fdir / ".resources" / "definition.yaml", {"$kind": "collection", "order": fi * 1000})
        wanted_files = set()
        for ri, it in enumerate(f["item"], start=1):
            r = it["request"]
            fname = _file_name(it["name"]) + ".request.yaml"
            wanted_files.add(fname)
            doc = {"$kind": "http-request"}
            if _file_name(it["name"]) != it["name"]:
                doc["name"] = it["name"]
            doc["url"] = r["url"]["raw"]
            doc["method"] = r["method"]
            if r["header"]:
                doc["headers"] = {h["key"]: h["value"] for h in r["header"]}
            if "body" in r:
                doc["body"] = {"type": "json", "content": _lit(r["body"]["raw"])}
            doc["scripts"] = _scripts(it["event"])
            doc["order"] = ri * 1000
            _dump_yaml(fdir / fname, doc)
        for stale in fdir.glob("*.request.yaml"):
            if stale.name not in wanted_files:
                stale.unlink()
    for d in target.iterdir():
        if d.is_dir() and d.name != ".resources" and d.name not in wanted_folders:
            shutil.rmtree(d)


def tracked_by_extension():
    if not EXT_RESOURCES.exists():
        return set()
    res = yaml.safe_load(EXT_RESOURCES.read_text(encoding="utf-8")) or {}
    paths = list(((res.get("cloudResources") or {}).get("collections") or {}).keys())
    paths += list((res.get("localResources") or {}).get("collections") or [])
    return {pathlib.PurePosixPath(p).name for p in paths}


def main():
    collections = [todo_service(), todo_mcp(), auth_service()]
    JSON_DIR.mkdir(exist_ok=True)
    tracked = tracked_by_extension()
    for c in collections:
        name = c["info"]["name"]
        data = json.dumps(c, indent=2, ensure_ascii=False) + "\n"
        (JSON_DIR / f"{name}.postman_collection.json").write_text(data, encoding="utf-8", newline="\n")
        print(f"json  postman-json/{name}.postman_collection.json")
        ext_json = EXT_DIR / f"{name}.postman_collection.json"
        if name in tracked:
            write_extension_yaml(c, EXT_COLLECTIONS / name)
            print(f"yaml  postman/postman/collections/{name}/  (extension-managed)")
            if ext_json.exists():
                ext_json.unlink()   # duplicate of the extension-managed copy
                print(f"del   postman/{name}.postman_collection.json  (duplicate)")
        elif EXT_DIR.exists():
            ext_json.write_text(data, encoding="utf-8", newline="\n")
            print(f"json  postman/{name}.postman_collection.json  (for the extension to import)")


if __name__ == "__main__":
    sys.exit(main())
