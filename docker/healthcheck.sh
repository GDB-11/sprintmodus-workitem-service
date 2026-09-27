#!/usr/bin/env bash
# Container health check: exits 0 when the service's liveness probe answers UP. The JRE image has neither curl nor wget,
# so this speaks plain HTTP over bash's /dev/tcp. PORT is set by the Dockerfile.
exec 3<>"/dev/tcp/127.0.0.1/${PORT:?}" || exit 1
printf 'GET /actuator/health/liveness HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n' >&3
grep -q '"status":"UP"' <&3
