#!/usr/bin/env python3
"""Mock OpenAI-compatible chat completions endpoint.

Every response deliberately contains fields the openai-java SDK does not model
(message.reasoning_content + top-level mock_unknown_field) so that Jackson's
any-setter putAdditionalProperty is exercised — the exact path that crashed the
native binary before the reflection metadata fix.

Content selection: request body containing 'image_url' (OCR multimodal call)
gets plain OCR text; text-only calls get a trade-draft JSON array the
TradeDraftParser expects.
"""
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

OCR_TEXT = """2026-08-28 09:31:05 买入 600745 闻泰科技 46.51 200
2026-08-28 13:45:12 买入 600745 闻泰科技 46.02 300
2026-08-28 14:55:40 卖出 600745 闻泰科技 47.18 500"""

DRAFT_JSON = '[[ "600745", "闻泰科技", "BUY", 46.51, 200, "2026-08-28 09:31:05" ],' \
             ' [ "600745", "闻泰科技", "SELL", 47.18, 500, "2026-08-28 14:55:40" ]]'


def completion(content: str) -> bytes:
    return json.dumps({
        "id": "chatcmpl-mock",
        "object": "chat.completion",
        "created": 1756730000,
        "model": "mock-model",
        "choices": [{
            "index": 0,
            "message": {
                "role": "assistant",
                "content": content,
                "refusal": None,
                # NOT modeled by openai-java 4.49 ChatCompletionMessage -> any-setter
                "reasoning_content": "mock reasoning to trigger putAdditionalProperty"
            },
            "finish_reason": "stop",
            "logprobs": None
        }],
        "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
        # top-level unknown field as well
        "mock_unknown_field": {"note": "unmodeled"}
    }).encode()


class Handler(BaseHTTPRequestHandler):
    # HTTP/1.1 keep-alive: okhttp clients break on premature HTTP/1.0 closes
    protocol_version = 'HTTP/1.1'

    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(length).decode('utf-8', 'ignore')
        content = OCR_TEXT if 'image_url' in body else DRAFT_JSON
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(content)))
        self.end_headers()
        self.wfile.write(completion(content))
        print(f'mock: {self.path} <- {"OCR" if "image_url" in body else "LLM"} response (200)', flush=True)

    def log_message(self, *args):
        pass


if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 18080), Handler).serve_forever()
