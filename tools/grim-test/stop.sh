#!/usr/bin/env bash
cd "$(dirname "$0")"
./cmd stop >/dev/null 2>&1 || true
for _ in $(seq 1 30); do kill -0 "$(cat server/server.pid 2>/dev/null)" 2>/dev/null || { echo stopped; exit 0; }; sleep 1; done
kill "$(cat server/server.pid)" 2>/dev/null; echo killed
