#!/usr/bin/env bash
set -euo pipefail

CADDYFILE="${CADDYFILE:-/etc/caddy/Caddyfile}"
BACKUP="${CADDYFILE}.pre-login-ui"
TMP_FILE="$(mktemp)"

cleanup() {
    rm -f "$TMP_FILE"
}
trap cleanup EXIT

cp "$CADDYFILE" "$BACKUP"

python3 - "$CADDYFILE" "$TMP_FILE" <<'PY'
from pathlib import Path
import re
import sys

source = Path(sys.argv[1])
target = Path(sys.argv[2])
text = source.read_text(encoding="utf-8")

# Remove earlier generated routing lines so this script is idempotent.
text = re.sub(r"^\s*@webApi path /tplanner/\*\s*$\n?", "", text, flags=re.MULTILINE)
text = re.sub(r"^\s*header @webApi -WWW-Authenticate\s*$\n?", "", text, flags=re.MULTILINE)
text = re.sub(
    r"\n\s*# tPlanner form-login errors begin.*?# tPlanner form-login errors end\s*\n",
    "\n",
    text,
    flags=re.DOTALL,
)

basic_pattern = re.compile(r"^(\s*)(?:basic_auth|basicauth)(?:\s+@webApi)?\s*\{", re.MULTILINE)
match = basic_pattern.search(text)
if not match:
    raise SystemExit("No Caddy basic-auth block found")

indent = match.group(1)
directive = match.group(0).split()[0]
replacement = f"{indent}@webApi path /tplanner/*\n{indent}{directive} @webApi {{"
text = basic_pattern.sub(replacement, text, count=1)

error_route = (
    f"{indent}# tPlanner form-login errors begin\n"
    f"{indent}handle_errors {{\n"
    f"{indent}    @authError expression {{err.status_code}} == 401\n"
    f"{indent}    handle @authError {{\n"
    f"{indent}        header -WWW-Authenticate\n"
    f"{indent}        respond \"Unauthorized\" 403\n"
    f"{indent}    }}\n"
    f"{indent}}}\n"
    f"{indent}# tPlanner form-login errors end\n\n"
)

proxy_pattern = re.compile(r"^(\s*)reverse_proxy\b", re.MULTILINE)
if not proxy_pattern.search(text):
    raise SystemExit("No reverse_proxy directive found")
text = proxy_pattern.sub(error_route + r"\1reverse_proxy", text, count=1)

target.write_text(text, encoding="utf-8")
PY

cp "$TMP_FILE" "$CADDYFILE"

if ! caddy validate --config "$CADDYFILE" --adapter caddyfile; then
    cp "$BACKUP" "$CADDYFILE"
    echo "Caddy validation failed; restored $BACKUP" >&2
    exit 1
fi

if ! systemctl restart caddy || ! systemctl is-active --quiet caddy; then
    cp "$BACKUP" "$CADDYFILE"
    systemctl restart caddy
    echo "Caddy restart failed; restored $BACKUP" >&2
    exit 1
fi

echo "Caddy Web form authentication configured."
