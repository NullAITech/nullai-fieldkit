"""OpenAPI 3.1 schema tailored for ChatGPT GPT Actions (no streaming endpoints)."""
from __future__ import annotations

from . import __version__

_ERR = {"description": "Error", "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Error"}}}}


def _resp(ref: str) -> dict:
    return {"200": {"description": "OK", "content": {"application/json": {"schema": {"$ref": f"#/components/schemas/{ref}"}}}},
            "401": _ERR, "403": _ERR, "429": _ERR}


def actions_schema(server_url: str) -> dict:
    return {
        "openapi": "3.1.0",
        "info": {"title": "zoth-relay", "version": __version__,
                 "description": "Sandboxed access to a ZOTHOS/Parrot Linux machine. Paths must be inside the "
                                "agent's allowed directories. Commands run as an unprivileged user."},
        "servers": [{"url": server_url.rstrip("/")}],
        "security": [{"bearerAuth": []}],
        "paths": {
            "/v1/status": {"get": {"operationId": "getStatus", "summary": "Machine and relay status",
                                   "responses": _resp("Status")}},
            "/v1/dir": {"get": {"operationId": "listDir", "summary": "List a directory",
                                "parameters": [{"name": "path", "in": "query", "required": False,
                                                "schema": {"type": "string"}, "description": "Directory; default is the first allowed dir"}],
                                "responses": _resp("DirListing")}},
            "/v1/file": {
                "get": {"operationId": "readFile", "summary": "Read a file",
                        "parameters": [{"name": "path", "in": "query", "required": True, "schema": {"type": "string"}}],
                        "responses": _resp("FileContent")},
                "post": {"operationId": "writeFile", "summary": "Write or append a text file",
                         "requestBody": {"required": True, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/FileWrite"}}}},
                         "responses": _resp("WriteResult")}},
            "/v1/exec/run": {"post": {"operationId": "runCommand",
                                      "summary": "Run a bash command and return its full output (non-streaming)",
                                      "requestBody": {"required": True, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/ExecRequest"}}}},
                                      "responses": _resp("ExecResult")}},
        },
        "components": {
            "securitySchemes": {"bearerAuth": {"type": "http", "scheme": "bearer"}},
            "schemas": {
                "Error": {"type": "object", "properties": {"detail": {"type": "string"}}},
                "Status": {"type": "object", "properties": {
                    "ok": {"type": "boolean"}, "version": {"type": "string"}, "agent": {"type": "string"},
                    "profile": {"type": "string"}, "service_uptime_s": {"type": "number"},
                    "allowed_dirs": {"type": "array", "items": {"type": "string"}}}},
                "DirListing": {"type": "object", "properties": {
                    "path": {"type": "string"},
                    "entries": {"type": "array", "items": {"type": "object", "properties": {
                        "name": {"type": "string"}, "type": {"type": "string", "enum": ["file", "dir", "symlink"]},
                        "size": {"type": "integer"}, "mtime": {"type": "integer"}}}}}},
                "FileContent": {"type": "object", "properties": {
                    "path": {"type": "string"}, "encoding": {"type": "string", "enum": ["utf-8", "base64"]},
                    "content": {"type": "string"}, "size": {"type": "integer"}}},
                "FileWrite": {"type": "object", "required": ["path", "content"], "properties": {
                    "path": {"type": "string"}, "content": {"type": "string"},
                    "append": {"type": "boolean", "default": False}, "make_dirs": {"type": "boolean", "default": True}}},
                "WriteResult": {"type": "object", "properties": {
                    "ok": {"type": "boolean"}, "path": {"type": "string"}, "bytes": {"type": "integer"}}},
                "ExecRequest": {"type": "object", "required": ["command"], "properties": {
                    "command": {"type": "string", "maxLength": 16384},
                    "working_directory": {"type": "string"},
                    "timeout": {"type": "integer", "minimum": 1, "description": "Seconds; capped by server max_timeout"}}},
                "ExecResult": {"type": "object", "properties": {
                    "stdout": {"type": "string"}, "stderr": {"type": "string"},
                    "exit_code": {"type": ["integer", "null"]}, "timed_out": {"type": "boolean"},
                    "truncated": {"type": "boolean"}, "duration_s": {"type": "number"}}},
            },
        },
    }
