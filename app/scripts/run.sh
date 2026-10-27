#!/usr/bin/env bash
# jar 与 application 目录同级
cd "$(dirname "$0")" || exit 1
exec java -Dfile.encoding=UTF-8 -Xms256m -Xmx512m -jar app.jar "$@"
