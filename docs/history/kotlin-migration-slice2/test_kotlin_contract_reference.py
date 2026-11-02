import ast
import importlib.util
import json
import os
import re
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VECTORS = ROOT / "termux/apk-shell/app/src/test/resources/contract-vectors.properties"


def vectors():
    return dict(line.split("=", 1) for line in VECTORS.read_text(encoding="utf-8").splitlines() if line)


def run_isolated(script, env):
    result = subprocess.run([sys.executable, "-c", script], cwd=ROOT, env=env,
                            capture_output=True, text=True, encoding="utf-8", timeout=60, check=True)
    return json.loads(result.stdout.strip().splitlines()[-1])


def assert_sse_response(test, status, content_type, body):
    test.assertEqual(200, status)
    test.assertEqual(vectors()["sse"], content_type.split(";", 1)[0])
    test.assertTrue(body.endswith(b"\n\n"))
    frames = body.split(b"\n\n")[:-1]
    test.assertTrue(frames)
    events = []
    for frame in frames:
        test.assertTrue(frame.startswith(b"data: "))
        test.assertNotIn(b"\n", frame)
        events.append(json.loads(frame[6:]))
    test.assertEqual(["session", "text", "done"], [event["type"] for event in events])
    test.assertEqual({"type": "session", "session_id": "contract-session"}, events[0])
    test.assertEqual({"type": "text", "text": "你好\nworld"}, events[1])
    test.assertEqual({"type": "done", "stop_reason": "end_turn", "usage": {
        "prompt_tokens": 2, "completion_tokens": 3, "total_tokens": 5,
    }}, events[2])


class ReferenceContractTest(unittest.TestCase):
    def test_routes_and_sse_match_backend(self):
        expected = vectors()
        source = (ROOT / "agentd/main.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        routes = {
            (decorator.func.attr, decorator.args[0].value)
            for node in ast.walk(tree)
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))
            for decorator in node.decorator_list
            if isinstance(decorator, ast.Call)
            and isinstance(decorator.func, ast.Attribute)
            and isinstance(decorator.func.value, ast.Name)
            and decorator.func.value.id == "app"
            and decorator.args
            and isinstance(decorator.args[0], ast.Constant)
            and isinstance(decorator.args[0].value, str)
        }
        for method, key in (("get", "health"), ("post", "chat"), ("get", "tools"), ("get", "export"), ("post", "import")):
            self.assertIn((method, expected[key]), routes)
        self.assertIn(expected["sse"], (ROOT / "docs/API.md").read_text(encoding="utf-8"))
        chat = next(node for node in ast.walk(tree) if isinstance(node, ast.AsyncFunctionDef) and any(
            isinstance(d, ast.Call) and d.args and isinstance(d.args[0], ast.Constant)
            and d.args[0].value == expected["chat"] for d in node.decorator_list))
        self.assertTrue(any(isinstance(n, ast.Call) and isinstance(n.func, ast.Name)
                            and n.func.id == "EventSourceResponse" for n in ast.walk(chat)))

    def test_events_and_risks_match_frontend(self):
        source = (ROOT / "agentd/web/src/api.ts").read_text(encoding="utf-8")
        events = source.split("export type ChatEvent =", 1)[1].split("export type ApprovalDecision", 1)[0]
        self.assertEqual(set(vectors()["events"].split(",")), set(re.findall(r'type:\s*"([a-z_]+)"', events)))
        risks = source.split("export interface ToolInfo", 1)[1].split("}", 1)[0]
        self.assertEqual(vectors()["risks"].split(","), re.findall(r'"(safe|write|danger)"', risks))

    def test_sse_response_and_frames_from_isolated_python_app(self):
        script = r'''
import asyncio, json, sys
from unittest.mock import patch
from fastapi.testclient import TestClient
import agentd.main as m

class Store:
    async def create_session(self):
        return "contract-session"

async def run(sid, message):
    queue = m.runs[sid]["queue"]
    await queue.put({"type": "text", "text": "你好\nworld"})
    await queue.put({"type": "done", "stop_reason": "end_turn", "usage": {
        "prompt_tokens": 2, "completion_tokens": 3, "total_tokens": 5}})

app = m.create_app()
with patch.object(m, "store", Store()), patch.object(m, "run_agent", run):
    m.runs.clear()
    client = TestClient(app)
    response = client.post("/api/chat", json={"message": "hello"})
    print(json.dumps({"status": response.status_code, "content_type": response.headers.get("content-type", ""),
                      "body": response.content.hex()}))
    client.close()
'''
        with tempfile.TemporaryDirectory() as home:
            env = os.environ.copy()
            env["AGENT_HOME"] = home
            env.pop("AGENT_TOKEN", None)
            result = subprocess.run([sys.executable, "-c", script], cwd=ROOT, env=env,
                                    capture_output=True, text=True, encoding="utf-8", timeout=60)
        self.assertEqual(0, result.returncode, result.stderr)
        response = json.loads(result.stdout.strip().splitlines()[-1])
        body = bytes.fromhex(response["body"])
        assert_sse_response(self, response["status"], response["content_type"], body)
        with self.assertRaises(AssertionError):
            assert_sse_response(self, response["status"], response["content_type"],
                                body.replace(b'"end_turn"', b'"wrong"'))
        with self.assertRaises(AssertionError):
            assert_sse_response(self, response["status"], "application/json", body)

    def test_rest_cases_against_isolated_reference(self):
        script = r'''
import json
from fastapi.testclient import TestClient
import agentd.main as m

m.MOCK = True
app = m.create_app()
bodies = {
    "session_create": {}, "settings_put": {"permission_mode": "approve"},
    "approval_invalid": {"session_id": "missing", "tool_call_id": "missing", "decision": "invalid"},
    "approval_missing": {"session_id": "missing", "tool_call_id": "missing", "decision": "allow_once"},
    "jobs_invalid": {"name": "test", "trigger_type": "cron", "expr": "bad", "message": "test"},
    "import_invalid": {}, "import_empty": {"sessions": []},
}
cases = json.loads(__import__("os").environ["CONTRACT_CASES"])
results = {}
with TestClient(app) as client:
    for name, spec in cases.items():
        method, path = spec.split("|")[:2]
        kwargs = {"json": bodies[name]} if name in bodies else {}
        response = client.request(method, path, **kwargs)
        data = response.json()
        results[name] = {"status": response.status_code, "fields": list(data) if isinstance(data, dict) else "@array"}
        if name == "jobs_delete_missing":
            results[name]["ok"] = data["ok"]
        if name == "import_empty":
            results[name]["imported"] = list(data["imported"])
print(json.dumps(results))
'''
        cases = {k.removeprefix("case."): value for k, value in vectors().items()
                 if k.startswith("case.") and k != "case.import_fields"}
        with tempfile.TemporaryDirectory() as home:
            env = os.environ.copy()
            env["AGENT_HOME"] = home
            env.pop("AGENT_TOKEN", None)
            env["CONTRACT_CASES"] = json.dumps(cases)
            actual = run_isolated(script, env)
        self.assertEqual(set(cases), set(actual))
        def assert_case(spec, response):
            _, _, status, fields = spec.split("|")
            self.assertEqual(int(status), response["status"])
            if fields == "@array":
                self.assertEqual(fields, response["fields"])
            else:
                self.assertTrue(set(fields.split(",")) <= set(response["fields"]))

        for name, spec in cases.items():
            with self.subTest(case=name):
                assert_case(spec, actual[name])
        self.assertIs(actual["jobs_delete_missing"]["ok"], False)
        self.assertEqual(set(vectors()["case.import_fields"].split(",")), set(actual["import_empty"]["imported"]))
        changed = dict(actual["approval_invalid"], status=200)
        with self.assertRaises(AssertionError):
            assert_case(cases["approval_invalid"], changed)

    def test_agent_stream_event_contract_and_producer_mutations(self):
        script = r'''
import asyncio, json, os
from pathlib import Path
from types import SimpleNamespace as NS
from agentd.config import Settings
from agentd.memory import Store
import agentd.agent as module

mutation = os.environ.get("CONTRACT_MUTATION", "")
if mutation:
    path = Path(module.__file__)
    source = path.read_text(encoding="utf-8")
    changes = {
        "type": ('{"type": "thinking", "text": reasoning}', '{"type": "thought", "text": reasoning}'),
        "field": ('{"type": "done", "stop_reason": "end_turn", "usage": self.usage_payload()}',
                  '{"type": "done", "reason": "end_turn", "usage": self.usage_payload()}'),
    }
    old, new = changes[mutation]
    if source.count(old) != 1:
        raise RuntimeError("mutation target is not unique")
    exec(compile(source.replace(old, new), str(path), "exec"), module.__dict__)

class Completions:
    async def create(self, **kwargs):
        async def chunks():
            for content, reasoning in [(None, "思考"), ("你好", None), ("世界", None)]:
                yield NS(usage=None, choices=[NS(delta=NS(content=content, reasoning_content=reasoning, tool_calls=None))])
            yield NS(usage=NS(prompt_tokens=2, completion_tokens=3, total_tokens=5), choices=[])
        return chunks()

async def main():
    store = Store(Path(os.environ["AGENT_HOME"]) / "agent.db")
    await store.start()
    try:
        sid = await store.create_session()
        cfg = Settings()
        cfg.data["permission_mode"] = "chat"
        agent = module.Agent(store, cfg, mock=False)
        agent._client = NS(chat=NS(completions=Completions()))
        events = []
        async def emit(event):
            events.append(event)
        await agent.chat(sid, "hello", emit=emit)
        messages = await store.get_messages(sid)
        print(json.dumps({"events": events, "assistant": messages[-1]["content"]}, ensure_ascii=True))
    finally:
        await store.close()

asyncio.run(main())
'''
        def run(mutation):
            with tempfile.TemporaryDirectory() as home:
                env = os.environ.copy()
                env["AGENT_HOME"] = home
                env.pop("AGENT_TOKEN", None)
                env["CONTRACT_MUTATION"] = mutation
                return run_isolated(script, env)

        def assert_contract(result):
            self.assertEqual([
                {"type": "thinking", "text": "思考"},
                {"type": "text", "text": "你好"},
                {"type": "text", "text": "世界"},
                {"type": "done", "stop_reason": "end_turn", "usage": {
                    "prompt_tokens": 2, "completion_tokens": 3, "total_tokens": 5}},
            ], result["events"])
            self.assertEqual("你好世界", result["assistant"])

        baseline = run("")
        assert_contract(baseline)
        for mutation in ("type", "field"):
            with self.subTest(mutation=mutation):
                mutated = run(mutation)
                expected = json.loads(json.dumps(baseline))
                if mutation == "type":
                    expected["events"][0]["type"] = "thought"
                else:
                    done = expected["events"][-1]
                    done["reason"] = done.pop("stop_reason")
                self.assertEqual(expected, mutated)
                with self.assertRaises(AssertionError):
                    assert_contract(mutated)

    def test_isolated_nonzero_exit_is_not_accepted(self):
        with self.assertRaises(subprocess.CalledProcessError) as caught:
            run_isolated('import sys; print("{} "); sys.exit(17)', os.environ.copy())
        self.assertEqual(17, caught.exception.returncode)

    def test_tool_defaults_match_actual_python_descriptor(self):
        spec = importlib.util.spec_from_file_location("reference_tools", ROOT / "agentd/tools/__init__.py")
        module = importlib.util.module_from_spec(spec)
        import sys
        sys.modules[spec.name] = module
        spec.loader.exec_module(module)
        v = vectors()
        tool = module.Tool(v["tool.name"], v["tool.description"], {}, v["tool.risk"], None)
        self.assertEqual((tool.name, tool.description, tool.risk, tool.summary, str(tool.timeout), tool.group),
                         (v["tool.name"], v["tool.description"], v["tool.risk"], v["tool.summary"], v["tool.timeout"], v["tool.group"]))


if __name__ == "__main__":
    unittest.main()
