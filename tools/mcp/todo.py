#!/usr/bin/env python3
"""Call the todo-mcp server, logging in through the browser when the token is missing or expired.

    python tools/mcp/todo.py token                                   # print a valid token
    python tools/mcp/todo.py call list_todos
    python tools/mcp/todo.py call create_todo '{"title": "Swim", "description": "Today"}'
    python tools/mcp/todo.py call delete_todo '{"id": 58}'
    python tools/mcp/todo.py logout                                  # forget the cached token

Login flow (loopback, like `gh auth login`):
  1. Start a one-shot listener on 127.0.0.1:<random port> with a random `state`.
  2. Open http://localhost/login?callback=http://127.0.0.1:<port>/callback&state=... in the browser.
  3. After a successful login the page POSTs {token, expires_in, state} to the listener.
  4. The listener checks `state` and the Origin, caches the token, and the call continues.

The password never reaches this script. The token is cached (until it expires) in
~/.apis_poc/token.json, or the path in the APIS_POC_TOKEN_FILE environment variable.
Standard library only.
"""
import argparse
import base64
import html
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from http.server import BaseHTTPRequestHandler, HTTPServer

PROTOCOL_VERSION = "2025-11-25"
EXPIRY_MARGIN_S = 30          # treat a token as expired this many seconds early
MAX_CALLBACK_BODY = 16 * 1024


class AuthRequired(Exception):
    """The server rejected the token (HTTP 401)."""


# --------------------------------------------------------------------------- token cache

def token_file():
    return os.environ.get("APIS_POC_TOKEN_FILE") or os.path.join(os.path.expanduser("~"), ".apis_poc", "token.json")


def jwt_exp(token):
    try:
        payload = token.split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return int(json.loads(base64.urlsafe_b64decode(payload))["exp"])
    except Exception:
        return 0


def load_cached_token():
    try:
        with open(token_file(), encoding="utf-8") as f:
            token = json.load(f).get("access_token", "")
    except (OSError, ValueError):
        return None
    return token if token and jwt_exp(token) - time.time() > EXPIRY_MARGIN_S else None


def save_token(token):
    path = token_file()
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"access_token": token, "exp": jwt_exp(token)}, f)
    try:
        os.chmod(path, 0o600)
    except OSError:
        pass


def clear_token():
    try:
        os.remove(token_file())
    except FileNotFoundError:
        pass


# --------------------------------------------------------------------------- browser login

def browser_login(base_url, email, timeout_s):
    base = urllib.parse.urlsplit(base_url)
    expected_origin = f"{base.scheme}://{base.netloc}"
    state = secrets.token_urlsafe(24)
    received = {}

    class Callback(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def _reply(self, status, title, text):
            body = (f"<!doctype html><meta charset=utf-8><title>{html.escape(title)}</title>"
                    "<body style='font-family:system-ui,sans-serif;max-width:560px;margin:64px auto;padding:0 16px'>"
                    f"<h1 style='font-size:1.4rem'>{html.escape(title)}</h1><p>{html.escape(text)}</p>").encode()
            self.send_response(status)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            self._reply(404, "Not found", "This address only accepts the login page's token.")

        def do_POST(self):
            if self.path != "/callback":
                return self._reply(404, "Not found", "Unknown path.")
            origin = self.headers.get("Origin")
            if origin and origin not in (expected_origin, "null"):
                return self._reply(403, "Rejected", f"Unexpected origin {origin}.")
            length = int(self.headers.get("Content-Length") or 0)
            if length <= 0 or length > MAX_CALLBACK_BODY:
                return self._reply(400, "Rejected", "Missing or oversized body.")
            form = urllib.parse.parse_qs(self.rfile.read(length).decode("utf-8", "replace"))
            token = (form.get("token") or [""])[0]
            if not secrets.compare_digest((form.get("state") or [""])[0], state):
                return self._reply(400, "Rejected", "State mismatch. Start the login again from the terminal.")
            if token.count(".") != 2:
                return self._reply(400, "Rejected", "No token received.")
            received["token"] = token
            self._reply(200, "Logged in", "The token was handed to the terminal. You can close this tab.")

    server = HTTPServer(("127.0.0.1", 0), Callback)
    server.timeout = 1
    port = server.server_address[1]
    query = urllib.parse.urlencode({"callback": f"http://127.0.0.1:{port}/callback", "state": state, "email": email})
    login_url = f"{expected_origin}/login?{query}"

    print("Token missing or expired. Opening the login page in your browser...", file=sys.stderr)
    print(f"If it doesn't open, go to: {login_url}", file=sys.stderr)
    webbrowser.open(login_url)

    deadline = time.time() + timeout_s
    try:
        while "token" not in received and time.time() < deadline:
            server.handle_request()
    finally:
        server.server_close()
    if "token" not in received:
        raise SystemExit(f"Login timed out after {timeout_s}s.")
    save_token(received["token"])
    print("Login received.", file=sys.stderr)
    return received["token"]


def get_token(args, force_login=False):
    if not force_login:
        token = load_cached_token()
        if token:
            return token
    return browser_login(args.base_url, args.email, args.timeout)


# --------------------------------------------------------------------------- MCP over Streamable HTTP

def post_mcp(base_url, token, message, session_id=None):
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "Authorization": f"Bearer {token}",
    }
    if message.get("method") != "initialize":
        headers["MCP-Protocol-Version"] = PROTOCOL_VERSION
    if session_id:
        headers["Mcp-Session-Id"] = session_id
    req = urllib.request.Request(f"{base_url}/mcp", data=json.dumps(message).encode(), headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=60) as res:
            sid = res.headers.get("Mcp-Session-Id") or session_id
            raw = res.read().decode("utf-8")
            ctype = res.headers.get("Content-Type", "")
    except urllib.error.HTTPError as e:
        if e.code == 401:
            raise AuthRequired() from None
        raise SystemExit(f"HTTP {e.code} from /mcp: {e.read().decode('utf-8', 'replace')}")
    if not raw.strip():
        return None, sid
    if "text/event-stream" in ctype:
        data = [line[5:].strip() for line in raw.splitlines() if line.startswith("data:")]
        raw = data[-1] if data else "{}"
    return json.loads(raw), sid


def call_tool(base_url, token, name, arguments):
    init = {"jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": PROTOCOL_VERSION, "capabilities": {},
                       "clientInfo": {"name": "apis-poc-todo-cli", "version": "1.0.0"}}}
    _, sid = post_mcp(base_url, token, init)
    post_mcp(base_url, token, {"jsonrpc": "2.0", "method": "notifications/initialized"}, sid)
    res, _ = post_mcp(base_url, token, {"jsonrpc": "2.0", "id": 2, "method": "tools/call",
                                         "params": {"name": name, "arguments": arguments}}, sid)
    return res


# --------------------------------------------------------------------------- CLI

def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--base-url", default=os.environ.get("APIS_POC_BASE_URL", "http://localhost"))
    p.add_argument("--email", default=os.environ.get("APIS_POC_EMAIL", "demo@example.com"),
                   help="pre-filled on the login page")
    p.add_argument("--timeout", type=int, default=300, help="seconds to wait for the browser login")
    sub = p.add_subparsers(dest="cmd", required=True)
    sub.add_parser("token", help="print a valid token, logging in if needed")
    sub.add_parser("login", help="always open the login page and cache the new token")
    sub.add_parser("logout", help="delete the cached token")
    c = sub.add_parser("call", help="call an MCP tool")
    c.add_argument("tool")
    c.add_argument("arguments", nargs="?", default="{}", help="JSON object of tool arguments")
    args = p.parse_args()
    args.base_url = args.base_url.rstrip("/")

    if args.cmd == "logout":
        clear_token()
        print("Cached token deleted.", file=sys.stderr)
        return
    if args.cmd in ("token", "login"):
        print(get_token(args, force_login=args.cmd == "login"))
        return

    try:
        arguments = json.loads(args.arguments)
        assert isinstance(arguments, dict)
    except (ValueError, AssertionError):
        raise SystemExit("arguments must be a JSON object, e.g. '{\"id\": 13}'")

    token = get_token(args)
    try:
        res = call_tool(args.base_url, token, args.tool, arguments)
    except AuthRequired:
        clear_token()   # rejected despite not being expired (e.g. secret rotated) -> log in again once
        token = get_token(args, force_login=True)
        res = call_tool(args.base_url, token, args.tool, arguments)

    if "error" in res:
        print(json.dumps(res["error"], indent=2))
        sys.exit(1)
    result = res.get("result", {})
    out = result.get("structuredContent")
    if out is None:
        out = [c.get("text") for c in result.get("content", [])]
    print(json.dumps(out, indent=2, ensure_ascii=False))
    if result.get("isError"):
        sys.exit(1)


if __name__ == "__main__":
    main()
