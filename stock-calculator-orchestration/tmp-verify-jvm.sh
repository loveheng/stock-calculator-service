#!/bin/bash
# 临时验证：initialized=false 时 orchestration 能否无经纪人完成启动
./tmp-repro-jvm.sh > /tmp/orch-smoke.log 2>&1 &
APP_PID=$!
OK=""
for i in $(seq 1 40); do
  if (exec 3<>/dev/tcp/127.0.0.1/18083) 2>/dev/null; then OK=1; exec 3>&-; break; fi
  if ! kill -0 "$APP_PID" 2>/dev/null; then break; fi
  sleep 1
done
if [ -n "$OK" ]; then echo "PROBE_OK :18083 可连"; else echo "PROBE_FAIL"; fi
grep -nE 'Started OrchestrationApplication|Tomcat started|APPLICATION FAILED|BeanCreationException|ERROR' /tmp/orch-smoke.log | head -10
kill "$APP_PID" 2>/dev/null
