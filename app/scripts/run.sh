#!/usr/bin/env bash
# application.yml 与 jar 同级
cd "$(dirname "$0")" || exit 1
exec java -Dfile.encoding=UTF-8 -Xms256m -Xmx512m -jar app.jar "$@"
