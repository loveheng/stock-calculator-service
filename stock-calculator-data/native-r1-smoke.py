#!/usr/bin/env python3
"""R1 PDFBox AOT 冒烟（native 二进制的公告处理全链，设计文档 R1 硬门槛）。

流程：本地起 PDF/LLM 双角色 HTTP 桩 → 启动 native 二进制（worker 角色，
llm.base-url 指向桩）→ 经管理台 API 向 stockcalc.tasks 发布
task.announcement.process 任务信封 → 轮询二进制日志直至 "announcement processed"
（PDF 下载→PDFBox 解析→建树→LLM 路由/蒸馏→接地→result 上行）→ 校验任务队列
清零、无死信 → 收尾清理队列。

前置：broker（guest/guest）运行中；target/stock-calculator-data-service 已构建。
用法：python3 native-r1-smoke.py；R1_JVM_AGENT=1 时以 GraalVM java +
native-image-agent 模式运行（采集 reflect/jni/resource 配置到 target/agent-configs/）
"""
import base64
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request
from http.server import BaseHTTPRequestHandler, HTTPServer

STUB_PORT = 18098
BIN_PORT = 19998
BROKER = "http://127.0.0.1:15672"
AUTH = "Basic " + base64.b64encode(b"guest:guest").decode()
RUN_LOG = "target/ni-r1-run.log"
# JVM + native-image-agent 模式（采集 awt JNI/PDFBox 反射/字体资源配置，R1 补课）
JVM_AGENT = os.environ.get("R1_JVM_AGENT") == "1"
AGENT_DIR = "target/agent-configs"
ANNOUNCEMENT_ID = "native-ann-001"

PDF_TEXTS = [
    "Native PDF Smoke Test Risk Factors Overview",
    "The company faces various operational risks including market competition",
    "and raw material price fluctuation. Management will continue to monitor",
    "the environment and adjust strategies to ensure stable development.",
    "Investors should read this section carefully and understand the risks.",
]


def build_pdf() -> bytes:
    """手工构造最小合法 PDF（Helvetica 单页多行），xref 偏移按字节精确计算。"""
    ops = [b"BT /F1 11 Tf 40 750 Td 14 TL"]
    for t in PDF_TEXTS:
        ops.append(b"(" + t.encode() + b") Tj T*")
    ops.append(b"ET")
    stream = b"\n".join(ops)
    objs = {
        1: b"<< /Type /Catalog /Pages 2 0 R >>",
        2: b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        3: (b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            b"/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>"),
        4: b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
        5: (b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n"
            + stream + b"\nendstream"),
    }
    out = bytearray(b"%PDF-1.4\n")
    offsets = {}
    for n in sorted(objs):
        offsets[n] = len(out)
        out += str(n).encode() + b" 0 obj\n" + objs[n] + b"\nendobj\n"
    xref_pos = len(out)
    maxn = max(objs) + 1
    out += b"xref\n0 " + str(maxn).encode() + b"\n"
    out += b"0000000000 65535 f \n"
    for n in range(1, maxn):
        out += ("%010d 00000 n \n" % offsets[n]).encode()
    out += (b"trailer\n<< /Size " + str(maxn).encode() + b" /Root 1 0 R >>\n"
            + b"startxref\n" + str(xref_pos).encode() + b"\n%%EOF")
    return bytes(out)


PDF = build_pdf()


class StubHandler(BaseHTTPRequestHandler):
    """GET /smoke.pdf → PDF；POST /chat/completions → 按“路由引擎”标记分流两段响应。"""

    def do_GET(self):
        if self.path == "/smoke.pdf":
            self.send_response(200)
            self.send_header("Content-Type", "application/pdf")
            self.send_header("Content-Length", str(len(PDF)))
            self.end_headers()
            self.wfile.write(PDF)
        else:
            self.send_response(404)
            self.send_header("Content-Length", "0")
            self.end_headers()

    def do_POST(self):
        body = self.read_body()
        # 路由请求识别：兼容原始 UTF-8 与 \uXXXX 转义两种形态（Jackson 转义行为不定）
        is_route = ("路由引擎" in body) or ("\\u8def\\u7531\\u5f15\\u64ce" in body)
        if not is_route:
            print("  [stub] 非 route 请求 CL=%s body[:200]=%r"
                  % (self.headers.get("Content-Length"), body[:200]))
        content = '["root"]' if is_route else "Native smoke summary of risk factors overview"
        resp = json.dumps({"choices": [{"message": {"content": content}}]}).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(resp)))
        self.end_headers()
        self.wfile.write(resp)

    def read_body(self):
        # Java RestClient 可能 chunked（无 Content-Length）：按块协议读全
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            chunks = []
            while True:
                size = int(self.rfile.readline().split(b";")[0].strip(), 16)
                if size == 0:
                    self.rfile.readline()
                    return b"".join(chunks).decode()
                chunks.append(self.rfile.read(size))
                self.rfile.readline()
        length = int(self.headers.get("Content-Length", 0))
        return self.rfile.read(length).decode()

    def log_message(self, *args):
        pass


def mgmt(path, data=None):
    url = BROKER + path
    req = (urllib.request.Request(url) if data is None
           else urllib.request.Request(url, data=json.dumps(data).encode(),
                                      method="POST",
                                      headers={"Content-Type": "application/json"}))
    req.add_header("Authorization", AUTH)
    # 沙箱 http_proxy 代理变量会劫持 localhost 请求，直连不走代理
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(req, timeout=10) as resp:
        return json.loads(resp.read() or b"{}")


def purge(queue):
    # LavinMQ 无 /purge 端点（404），真删用 DELETE /contents
    req = urllib.request.Request(BROKER + "/api/queues/%2F/" + queue + "/contents",
                                 method="DELETE")
    req.add_header("Authorization", AUTH)
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open(req, timeout=10) as resp:
            resp.read()
    except Exception as exc:  # noqa: BLE001
        print("  (purge %s skipped: %s)" % (queue, exc))


def read_log() -> bytes:
    try:
        with open(RUN_LOG, "rb") as handle:
            return handle.read()
    except FileNotFoundError:
        return b""


def main():
    os.chdir(os.path.dirname(os.path.abspath(__file__)))
    server = HTTPServer(("127.0.0.1", STUB_PORT), StubHandler)
    threading.Thread(target=server.serve_forever, daemon=True).start()

    env = dict(os.environ)
    # 剥离沙箱代理变量：二进制内的 RestClient（LLM 桩/PDF 下载）需直连 127.0.0.1
    for key in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "all_proxy", "ALL_PROXY"):
        env.pop(key, None)
    env["no_proxy"] = "localhost,127.0.0.1"
    env["SPRING_APPLICATION_JSON"] = json.dumps({"datasvc": {
        "llm": {"base-url": "http://127.0.0.1:%d" % STUB_PORT,
                "api-key": "r1-dummy", "model": "r1-dummy"},
        "worker": {"enabled": True,
                   "embedding": {"account-id": "r1-dummy", "api-token": "r1-dummy"}},
        "ingest": {"secret": "r1-dummy"},
    }})
    if JVM_AGENT:
        os.makedirs(AGENT_DIR, exist_ok=True)
        with open("target/jvm-classpath.txt") as cp_file:
            classpath = cp_file.read().strip()
        command = ["/opt/GraalVM25/bin/java",
                   "-Djava.awt.headless=true",
                   "-agentlib:native-image-agent=config-output-dir=" + AGENT_DIR,
                   "-cp", "target/classes:" + classpath,
                   "com.zzh.stock_calculator.data.DataServiceApplication",
                   "--server.port=%d" % BIN_PORT]
    else:
        command = ["./target/stock-calculator-data-service",
                   "-Djava.awt.headless=true", "--server.port=%d" % BIN_PORT]
    with open(RUN_LOG, "wb") as log:
        proc = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env)
    try:
        started = False
        # agent 插桩下 JVM 启动可达 ~63s，窗口放宽到 120s
        for _ in range(120):
            time.sleep(1)
            if b"Started DataServiceApplication" in read_log():
                started = True
                break
        if not started:
            print("R1 FAIL: 二进制 120s 内未启动，见 " + RUN_LOG)
            return 1

        for q in ("task.announcement.process.q", "task.announcement.process.q.retry",
                  "result.ingest.q", "dead.q"):
            purge(q)
        envelope = {
            "messageId": "m-native-r1", "type": "task.announcement.process",
            "schemaVersion": 1, "occurredAt": 1790000000000,
            "traceId": "native-r1-smoke", "producer": "stockcalc-main",
            "payload": {"announcementId": ANNOUNCEMENT_ID, "title": "Native Smoke",
                        "adjunctUrl": "http://127.0.0.1:%d/smoke.pdf" % STUB_PORT,
                        "secCode": "600000"},
        }
        mgmt("/api/exchanges/%2F/stockcalc.tasks/publish", {
            "properties": {"content_type": "application/json"},
            "routing_key": "task.announcement.process",
            "payload": json.dumps(envelope), "payload_encoding": "string"})
        print("task 已发布，等待 native worker 处理 ...")

        # agent 插桩下 PDF 全链变慢，处理窗口同步放宽
        deadline = time.time() + (90 if JVM_AGENT else 40)
        passed = False
        while time.time() < deadline:
            time.sleep(2)
            log_bytes = read_log()
            if ("announcement processed announcementId=" + ANNOUNCEMENT_ID).encode() in log_bytes:
                passed = True
                break
            text = log_bytes.decode(errors="replace")
            if "解析失败 attempt=2" in text or "阶段二蒸馏降级响应" in text:
                print("R1 FAIL: worker 终态失败（路由/蒸馏两次耗尽），见 " + RUN_LOG)
                return 1
        if not passed:
            print("R1 FAIL: 处理窗口内未见 done 日志，见 " + RUN_LOG)
            return 1

        time.sleep(1)
        task_depth = mgmt("/api/queues/%2F/task.announcement.process.q").get("messages", -1)
        dead_depth = mgmt("/api/queues/%2F/dead.q").get("messages", -1)
        print("队列终态: task=%d dead=%d" % (task_depth, dead_depth))
        if task_depth != 0 or dead_depth != 0:
            print("R1 FAIL: 任务队列未清零或出现死信")
            return 1
        print("R1 PASS: native 二进制 PDFBox 解析 + LLM 蒸馏 + result 上行全链通过")
        return 0
    finally:
        proc.terminate()
        try:
            proc.wait(timeout=30)
        except Exception:  # noqa: BLE001
            proc.kill()
        for q in ("task.announcement.process.q", "task.announcement.process.q.retry",
                  "result.ingest.q", "dead.q"):
            purge(q)
        server.shutdown()


if __name__ == "__main__":
    sys.exit(main())
