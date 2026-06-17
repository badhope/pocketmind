"""
MobiAgent Phone Runner - 主程序
使用 llama.cpp + GGUF 模型在 Android 设备上本地运行 GUI Agent

用法:
    python mobiagent.py --model minicpm-v-4.6
    python mobiagent.py --model auto
    python mobiagent.py --model-path /path/to/model.gguf
    python mobiagent.py --list-models
"""

from openai import OpenAI
import uiautomator2 as u2
import base64
from PIL import Image, ImageDraw
import json
import io
import logging
from abc import ABC, abstractmethod
import time
import re
import os
import argparse
import textwrap

from pathlib import Path
import sys
import tempfile

# 新:导入 LLM 后端
from llm_backend import (
    LlamaServer,
    LLMBackend,
    MODEL_REGISTRY,
    get_model_info,
    auto_select_model,
    get_ram_gb,
    resolve_model_path,
    _CONFIG as CONFIG,
)

# 从配置读取最大步数(默认 35)
MAX_STEPS = CONFIG.get("agent", {}).get("max_steps", 35)

# ============================================================================
# LLM 后端管理
# ============================================================================
_llm_server: LlamaServer = None
_llm_backend: LLMBackend = None


def init_local_llm(
    model_name: str = "auto",
    model_path: str = None,
    mmproj_path: str = None,
    port: int = None,
    ctx_size: int = None,
):
    """
    初始化本地 LLM 后端

    Args:
        model_name: 模型名称(auto 会自动选择)
        model_path: 直接指定模型路径(优先)
        mmproj_path: 视觉模型 mmproj 路径
        port: llama-server 端口
        ctx_size: 上下文长度
    """
    global _llm_server, _llm_backend

    server_cfg = CONFIG.get("server", {})
    port = port or server_cfg.get("port", 8080)
    ctx_size = ctx_size or server_cfg.get("ctx_size", 4096)
    n_gpu_layers = server_cfg.get("n_gpu_layers", 0)
    n_threads = server_cfg.get("n_threads")
    startup_timeout = server_cfg.get("startup_timeout", 60)

    if model_path:
        # 直接指定路径
        _llm_server = LlamaServer(
            model_path=model_path,
            mmproj_path=mmproj_path,
            port=port,
            n_gpu_layers=n_gpu_layers,
            ctx_size=ctx_size,
            n_threads=n_threads,
            startup_timeout=startup_timeout,
        )
    else:
        # 从注册表选择
        if model_name == "auto":
            ram = get_ram_gb()
            model_name = auto_select_model(ram)
            print(f"自动选择模型: {model_name} (RAM={ram}GB)")

        paths = resolve_model_path(model_name)
        _llm_server = LlamaServer(
            model_path=paths["model"],
            mmproj_path=paths["mmproj"],
            port=port,
            n_gpu_layers=n_gpu_layers,
            ctx_size=ctx_size,
            n_threads=n_threads,
            startup_timeout=startup_timeout,
        )

    print(f"启动 llama-server...")
    _llm_server.start()

    _llm_backend = LLMBackend(_llm_server)
    print(f"LLM 后端就绪 (端口 {port})")


def local_llm_generate(prompt: str, image_path: str = None) -> str:
    """
    调用本地 LLM 生成(兼容旧接口)

    Args:
        prompt: 文本 prompt
        image_path: 可选图片路径

    Returns:
        模型生成的文本
    """
    if _llm_backend is None:
        raise RuntimeError("LLM 后端未初始化,请先调用 init_local_llm()")
    return _llm_backend.generate(prompt, image_path)


def shutdown_local_llm():
    """关闭本地 LLM 服务"""
    global _llm_server, _llm_backend
    if _llm_server:
        _llm_server.stop()
        _llm_server = None
        _llm_backend = None


def list_models():
    """列出所有可用模型"""
    print("\n可用模型:")
    print("-" * 70)
    for name, info in MODEL_REGISTRY.items():
        vision = "视觉" if info["is_vision"] else "纯文本"
        print(f"  {name}")
        print(f"    名称: {info['name']}")
        print(f"    大小: {info['size_gb']} GB | RAM: {info['ram_gb']} GB | {vision}")
        print(f"    协议: {info['license']}")
        print(f"    说明: {info['description']}")
        print()


# ============================================================================
# Device 抽象层(保持不变)
# ============================================================================
class Device(ABC):
    @abstractmethod
    def start_app(self, app):
        pass

    @abstractmethod
    def app_stop(self, package_name):
        pass

    @abstractmethod
    def screenshot(self, path):
        pass

    @abstractmethod
    def click(self, x, y):
        pass

    @abstractmethod
    def input(self, text):
        pass

    @abstractmethod
    def swipe(self, direction):
        pass

    @abstractmethod
    def keyevent(self, key):
        pass

    @abstractmethod
    def dump_hierarchy(self):
        pass


class AndroidDevice(Device):
    def __init__(self, adb_endpoint=None):
        super().__init__()
        if adb_endpoint:
            self.d = u2.connect(adb_endpoint)
        else:
            self.d = u2.connect()
        self.app_package_names = {
            "携程": "ctrip.android.view",
            "同城": "com.tongcheng.android",
            "飞猪": "com.taobao.trip",
            "去哪儿": "com.Qunar",
            "华住会": "com.htinns",
            "饿了么": "me.ele",
            "支付宝": "com.eg.android.AlipayGphone",
            "淘宝": "com.taobao.taobao",
            "京东": "com.jingdong.app.mall",
            "美团": "com.sankuai.meituan",
            "滴滴出行": "com.sdu.didi.psnger",
            "微信": "com.tencent.mm",
            "微博": "com.sina.weibo",
            "华为商城": "com.vmall.client",
            "华为视频": "com.huawei.himovie",
            "华为音乐": "com.huawei.music",
            "华为应用市场": "com.huawei.appmarket",
            "拼多多": "com.xunmeng.pinduoduo",
            "大众点评": "com.dianping.v1",
            "小红书": "com.xingin.xhs",
            "浏览器": "com.microsoft.emmx",
        }

    def start_app(self, app):
        package_name = self.app_package_names.get(app)
        if not package_name:
            raise ValueError(f"App '{app}' is not registered with a package name.")
        self.d.app_start(package_name, stop=True)
        time.sleep(1)
        if not self.d.app_wait(package_name, timeout=20):
            raise RuntimeError(
                f"Failed to start app '{app}' with package '{package_name}'"
            )

    def app_start(self, package_name):
        self.d.app_start(package_name, stop=True)
        time.sleep(2)
        if not self.d.app_wait(package_name, timeout=10):
            raise RuntimeError(f"Failed to start package '{package_name}'")

    def app_stop(self, package_name):
        self.d.app_stop(package_name)

    def screenshot(self, path):
        self.d.screenshot(path)

    def click(self, x, y):
        self.d.click(x, y)
        time.sleep(0.5)

    def input(self, text):
        current_ime = self.d.current_ime()
        self.d.shell(
            [
                "settings",
                "put",
                "secure",
                "default_input_method",
                "com.android.adbkeyboard/.AdbIME",
            ]
        )
        time.sleep(0.5)
        charsb64 = base64.b64encode(text.encode("utf-8")).decode("utf-8")
        self.d.shell(
            ["am", "broadcast", "-a", "ADB_INPUT_B64", "--es", "msg", charsb64]
        )
        time.sleep(0.5)
        self.d.shell(["settings", "put", "secure", "default_input_method", current_ime])
        time.sleep(0.5)
        self.d.press("back")
        time.sleep(0.2)

    def swipe(self, direction, scale=0.5):
        self.d.swipe_ext(direction=direction, scale=scale)

    def keyevent(self, key):
        self.d.keyevent(key)

    def dump_hierarchy(self):
        return self.d.dump_hierarchy()


# ============================================================================
# 云端客户端(保留兼容)
# ============================================================================
decider_client = None
grounder_client = None
planner_client = None

planner_model = "planner"
decider_model = "decider"
grounder_model = "grounder"

use_local_llm_planner = False
use_local_llm_grounder = False
use_local_llm_decider = False


def init(
    service_ip,
    decider_port,
    grounder_port,
    planner_port,
    enable_user_profile=False,
    use_graphrag=False,
    use_experience_rr=False,
    use_local_planner=False,
    use_local_grounder=False,
    use_local_decider=False,
):
    global decider_client, grounder_client, planner_client
    global use_local_llm_planner, use_local_llm_grounder, use_local_llm_decider

    use_local_llm_planner = use_local_planner
    use_local_llm_grounder = use_local_grounder
    use_local_llm_decider = use_local_decider

    if not use_local_llm_decider:
        decider_client = OpenAI(
            api_key="0",
            base_url=f"http://{service_ip}:{decider_port}/v1",
        )
    if not use_local_llm_grounder:
        grounder_client = OpenAI(
            api_key="0",
            base_url=f"http://{service_ip}:{grounder_port}/v1",
        )
    if not use_local_llm_planner:
        planner_client = OpenAI(
            api_key="0",
            base_url=f"http://{service_ip}:{planner_port}/v1",
        )


# 截图缩放比例
factor = 1

from pydantic import BaseModel, Field
from typing import Any, Literal, Dict, Optional, Union
from enum import Enum


class ActionType(str, Enum):
    CLICK = "click"
    INPUT = "input"
    SWIPE = "swipe"
    DONE = "done"
    STOP = "stop"
    TERMINATE = "terminate"
    WAIT = "wait"


class ActionPlan(BaseModel):
    reasoning: str = Field(description="描述执行此动作的思考过程和理由。")
    action: ActionType = Field(description="要执行的下一个动作。")
    parameters: Dict[str, str] = Field(
        description="执行动作所需要的参数,以键值对形式提供。",
        default_factory=dict,
    )


json_schema = ActionPlan.model_json_schema()


class GroundResponse(BaseModel):
    coordinates: Optional[list[int]] = Field(
        description="点击坐标 [x, y]", default=None
    )
    bbox: Optional[list[int]] = Field(
        description="边界框 [x1, y1, x2, y2]", default=None
    )
    bbox_2d: Optional[list[int]] = Field(
        description="边界框 [x1, y1, x2, y2]", default=None
    )


json_schema_ground = GroundResponse.model_json_schema()


# ============================================================================
# 工具函数(保持不变)
# ============================================================================
def parse_json_response(response_str: str) -> dict:
    print("Parsing JSON response...")
    try:
        return json.loads(response_str)
    except json.JSONDecodeError:
        try:
            json_match = re.search(
                r"```json\s*(\{.*?\})\s*```", response_str, re.DOTALL
            )
            if json_match:
                return json.loads(json_match.group(1))

            json_match = re.search(r"(\{.*?\})", response_str, re.DOTALL)
            if json_match:
                return json.loads(json_match.group(1))

            raise ValueError("无法在响应中找到有效的JSON")
        except Exception as e:
            logging.error(f"JSON解析失败: {e}")
            logging.error(f"原始响应: {response_str}")
            raise ValueError(f"无法解析JSON响应: {e}")


def get_screenshot(device, device_type="Android"):
    if device_type == "Android":
        screenshot_path = "screenshot-Android.jpg"
    else:
        screenshot_path = "screenshot-Harmony.jpg"
    device.screenshot(screenshot_path)
    img = Image.open(screenshot_path)
    img = img.resize(
        (int(img.width * factor), int(img.height * factor)),
        Image.Resampling.LANCZOS,
    )
    buffered = io.BytesIO()
    img.save(buffered, format="JPEG")
    screenshot = base64.b64encode(buffered.getvalue()).decode("utf-8")
    return screenshot


def convert_qwen3_coordinates_to_absolute(
    bbox_or_coords, img_width, img_height, is_bbox=True
):
    if is_bbox:
        x1, y1, x2, y2 = bbox_or_coords
        x1 = int(x1 / 1000 * img_width)
        x2 = int(x2 / 1000 * img_width)
        y1 = int(y1 / 1000 * img_height)
        y2 = int(y2 / 1000 * img_height)
        return [x1, y1, x2, y2]
    else:
        x, y = bbox_or_coords
        x = int(x / 1000 * img_width)
        y = int(y / 1000 * img_height)
        return [x, y]


def create_swipe_visualization(data_dir, image_index, direction):
    try:
        img_path = os.path.join(data_dir, f"{image_index}.jpg")
        if not os.path.exists(img_path):
            return

        img = Image.open(img_path)
        width, height = img.size

        center_x, center_y = width // 2, height // 2
        arrow_length = min(width, height) // 4

        if direction == "up":
            start_point = (center_x, center_y + arrow_length // 2)
            end_point = (center_x, center_y - arrow_length // 2)
        elif direction == "down":
            start_point = (center_x, center_y - arrow_length // 2)
            end_point = (center_x, center_y + arrow_length // 2)
        elif direction == "left":
            start_point = (center_x + arrow_length // 2, center_y)
            end_point = (center_x - arrow_length // 2, center_y)
        elif direction == "right":
            start_point = (center_x - arrow_length // 2, center_y)
            end_point = (center_x + arrow_length // 2, center_y)
        else:
            return

        draw = ImageDraw.Draw(img)
        draw.line(
            [start_point, end_point], fill=(255, 0, 0), width=8
        )

        text = f"SWIPE {direction.upper()}"
        draw.text((width // 2 - 80, 20), text, fill=(255, 0, 0))

        swipe_path = os.path.join(data_dir, f"{image_index}_swipe.jpg")
        img.save(swipe_path)

    except Exception as e:
        logging.warning(f"Failed to create swipe visualization: {e}")


def robust_json_loads(s):
    s = s.strip()
    codeblock = re.search(r"```json(.*?)```", s, re.DOTALL)
    if codeblock:
        s = codeblock.group(1).strip()
    s = s.replace("…", "...").replace("\r", "").replace("\n", " ")

    try:
        return json.loads(s)
    except json.decoder.JSONDecodeError as e:
        if "Expecting ',' delimiter" in str(e):
            fields = [
                "reasoning",
                "thought",
                "action",
                "step",
                "parameters",
                "target_element",
            ]
            field_pattern = "|".join(re.escape(f) for f in fields)

            str_lit = r'"(?:[^"\\]|\\.)*"'

            pattern1 = rf'("({field_pattern})"\s*:\s*"((?:[^"\\]|\\.)*)?)(\s*"({field_pattern})"\s*:)'
            fixed_s1 = re.sub(pattern1, r'\1",\4', s)

            pattern2 = rf'("({field_pattern})"\s*:\s*{str_lit})(\s*"({field_pattern})"\s*:)'
            fixed_s2 = re.sub(pattern2, r"\1,\3", s)

            for candidate in [fixed_s1, fixed_s2]:
                if candidate != s:
                    try:
                        return json.loads(candidate)
                    except json.JSONDecodeError:
                        continue

            generic_pattern = r'("[^"]*?")(\s*"[a-zA-Z_][a-zA-Z0-9_]*"\s*:)'
            generic_fixed = re.sub(generic_pattern, r"\1,\2", s)
            if generic_fixed != s:
                try:
                    return json.loads(generic_fixed)
                except:
                    pass

        if "Extra data" in str(e):
            try:
                decoder = json.JSONDecoder()
                obj, end = decoder.raw_decode(s)
                logging.warning(
                    f"Extra data detected. Parsed valid JSON up to position {end}."
                )
                return obj
            except Exception:
                pass

        logging.error(f"解析 decider_response_str 失败: {e}\n原始内容: {s}")
        raise

    except Exception as e:
        logging.error(f"解析 decider_response_str 失败: {e}\n原始内容: {s}")
        raise


def load_prompt(md_name):
    """从 prompts 目录加载 prompt 模板(支持 phone_runner/prompts 和上级 prompts)

    优先查找 phone_runner/prompts,回退到上级目录 prompts。
    若两处均不存在,抛出 FileNotFoundError 并列出已搜索路径。
    """
    current_dir = Path(__file__).resolve().parent

    # 候选搜索路径(按优先级)
    candidates = [
        current_dir / "prompts" / md_name,
        current_dir.parent / "prompts" / md_name,
    ]

    for prompt_file in candidates:
        if prompt_file.exists():
            content = prompt_file.read_text(encoding="utf-8")
            content = content.replace("````markdown", "").replace("````", "")
            return content.strip()

    # 所有路径均不存在,给出清晰的错误信息
    searched = "\n  ".join(str(p) for p in candidates)
    raise FileNotFoundError(
        f"Prompt 模板 '{md_name}' 未找到。已搜索以下路径:\n  {searched}"
    )


import xml.etree.ElementTree as ET
from collections import Counter


def load_and_parse_xml(xml_path):
    with open(xml_path, "r", encoding="utf-8") as f:
        content = f.read()
    if not content.strip().startswith("<"):
        start = content.find("<")
        if start != -1:
            content = content[start:]
    return ET.fromstring(content)


def char_overlap_score(s1, s2):
    c1, c2 = Counter(s1), Counter(s2)
    intersection = (c1 & c2).elements()
    return sum(1 for _ in intersection)


def is_likely_garbled(text):
    if not text:
        return False

    def is_useful_char(ch):
        if "\u4e00" <= ch <= "\u9fff":
            return True
        if ch.isalpha() and ord(ch) < 128:
            return True
        if ch.isdigit():
            return True
        if ch in "，。！？；：""''（）【】《》〈〉、,.!?;:\"'()[]{}<> \t\n":
            return True
        if ch in "@#$%&*+-=/_":
            return True
        return False

    if any(is_useful_char(ch) for ch in text):
        return False
    return True


flag = False


def find_best_match_node(root, target_element):
    global flag
    candidates = []
    target_clean = target_element.strip()
    if not target_clean:
        return None

    for node in root.iter():
        text = node.get("text", "").strip()
        if not text:
            text = node.get("content-desc", "").strip()
            if not text:
                continue
        if is_likely_garbled(text):
            text = node.get("content-desc", "").strip()
            if not text:
                continue

        text1 = node.get("content-desc", "").strip()
        id = node.get("resource-id", "").strip()

        score = char_overlap_score(target_clean, text)

        if score > 1:
            bounds_str = node.get("bounds", "")
            if not bounds_str:
                continue
            try:
                coords = bounds_str.strip("[]").replace("][", ",").split(",")
                x1, y1, x2, y2 = map(int, coords)
                if ("搜索栏" in text) and flag == False:
                    flag = True
                    bounds = [x1, y1, x2, y2]
                    return bounds

                candidates.append(
                    {
                        "text": text,
                        "content-desc": text1,
                        "score": score,
                        "length": len(text),
                        "bounds": [x1, y1, x2, y2],
                    }
                )
            except Exception:
                continue
        else:
            bounds_str = node.get("bounds", "")
            if not bounds_str:
                continue
            if (
                ("搜索栏" in target_clean or "搜索框" in target_clean)
                and (id == "com.taobao.taobao:id/searchEdit")
            ):
                flag = True
                coords = bounds_str.strip("[]").replace("][", ",").split(",")
                x1, y1, x2, y2 = map(int, coords)
                bounds = [x1, y1, x2, y2]
                return bounds

    if not candidates:
        return None

    candidates.sort(key=lambda x: (-x["score"], x["length"]))
    flag = False
    return candidates[0]["bounds"]


# ============================================================================
# 核心任务执行(保持原有逻辑,仅替换 LLM 调用)
# ============================================================================
def task_in_app(
    app,
    task,
    device,
    data_dir,
    bbox_flag=True,
    use_qwen3=True,
    device_type="Android",
    use_grounder=True,
):
    history = []
    actions = []
    reacts = []
    global use_local_llm_planner
    global use_local_llm_grounder
    global use_local_llm_decider

    if use_qwen3:
        grounder_prompt_template_bbox = load_prompt("grounder_qwen3_bbox.md")
        grounder_prompt_template_no_bbox = load_prompt(
            "grounder_qwen3_coordinates.md"
        )
        decider_prompt_template = load_prompt("decider_v2.md")
    else:
        grounder_prompt_template_bbox = load_prompt("grounder_bbox.md")
        grounder_prompt_template_no_bbox = load_prompt("grounder_coordinates.md")
        decider_prompt_template = load_prompt("decider_v2.md")

    # 重置搜索栏匹配标志(防止跨任务状态泄漏)
    global flag
    flag = False

    while True:
        if len(actions) >= MAX_STEPS:
            print("Reached maximum steps, stopping the task.")
            break

        # 初始化本步变量(防止未定义)
        image_index = len(actions) + 1

        if len(history) == 0:
            history_str = "(No history)"
        else:
            history_str = "\n".join(
                f"{idx}. {h}" for idx, h in enumerate(history, 1)
            )
        screenshot_resize = get_screenshot(device, device_type)

        decider_prompt = decider_prompt_template.format(
            task=task, history=history_str
        )
        print(f"Decider prompt: \n{decider_prompt}")

        decider_start_time = time.time()

        if use_local_llm_decider:
            with tempfile.NamedTemporaryFile(
                suffix=".png", delete=False, dir="."
            ) as tmp_img:
                tmp_img_path = tmp_img.name
                src_path = (
                    "screenshot-Android.jpg"
                    if device_type == "Android"
                    else "screenshot-Harmony.jpg"
                )
                img = Image.open(src_path)
                img.save(tmp_img_path, "PNG")
            try:
                decider_response_str = local_llm_generate(
                    decider_prompt, tmp_img_path
                )
            finally:
                if os.path.exists(tmp_img_path):
                    os.remove(tmp_img_path)
        else:
            decider_response_obj = decider_client.chat.completions.create(
                model=decider_model,
                messages=[
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "image_url",
                                "image_url": {
                                    "url": f"data:image/jpeg;base64,{screenshot_resize}"
                                },
                            },
                            {"type": "text", "text": decider_prompt},
                        ],
                    }
                ],
                temperature=0,
                response_format={
                    "type": "json_object",
                    "schema": json_schema,
                },
            )
            decider_response_str = decider_response_obj.choices[0].message.content

        decider_end_time = time.time()
        print(f"Decider time taken: {decider_end_time - decider_start_time} seconds")
        print(f"Decider response: \n{decider_response_str}")

        decider_response = robust_json_loads(decider_response_str)
        action = decider_response["action"]

        if action != "done":
            converted_item = {
                "reasoning": decider_response["reasoning"],
                "function": {
                    "name": decider_response["action"],
                    "parameters": decider_response["parameters"],
                },
            }
            reacts.append(converted_item)

            image_index = len(actions) + 1
            current_dir = os.getcwd()
            current_image = ""
            if device_type == "Android":
                img_path = os.path.join(current_dir, f"screenshot-Android.jpg")
                save_path = os.path.join(data_dir, f"{image_index}.jpg")
                current_image = f"screenshot-Android.jpg"
            else:
                img_path = os.path.join(current_dir, f"screenshot-Harmony.jpg")
                save_path = os.path.join(data_dir, f"{image_index}.jpg")
                current_image = f"screenshot-Harmony.jpg"
            img = Image.open(img_path)
            img.save(save_path)

            if reacts:
                try:
                    reacts[-1]["action_index"] = image_index
                except Exception:
                    pass

            hierarchy = device.dump_hierarchy()

            if device_type == "Android":
                hierarchy_path = os.path.join(data_dir, f"{image_index}.xml")
                with open(hierarchy_path, "w", encoding="utf-8") as f:
                    f.write(hierarchy)
            else:
                hierarchy_path = os.path.join(data_dir, f"{image_index}.json")
                try:
                    if isinstance(hierarchy, str):
                        hierarchy_json = json.loads(hierarchy)
                    else:
                        hierarchy_json = hierarchy
                    with open(hierarchy_path, "w", encoding="utf-8") as f:
                        json.dump(hierarchy_json, f, ensure_ascii=False, indent=2)
                except (json.JSONDecodeError, TypeError):
                    logging.warning("Failed to parse hierarchy as JSON, saving as plain text")
                    with open(hierarchy_path, "w", encoding="utf-8") as f:
                        f.write(str(hierarchy))

        history.append(decider_response_str)
        if action == "done":
            print("Task completed.")
            actions.append({"type": "done", "action_index": image_index})
            break
        elif action == "click":
            reasoning = decider_response["reasoning"]
            target_element = decider_response["parameters"]["target_element"]
            grounder_prompt = (
                grounder_prompt_template_bbox
                if bbox_flag
                else grounder_prompt_template_no_bbox
            ).format(reasoning=reasoning, description=target_element)

            if bbox_flag:
                xml_path = os.path.join(data_dir, f"{image_index}.xml")
                try:
                    root = load_and_parse_xml(xml_path)
                    # use_grounder=True 时跳过 XML 匹配,直接用 grounder
                    # use_grounder=False 时优先 XML 匹配,失败再回退 grounder
                    bbox = None if use_grounder else find_best_match_node(
                        root, target_element
                    )
                    if bbox is None:
                        grounder_start_time = time.time()
                        if use_local_llm_grounder:
                            with tempfile.NamedTemporaryFile(
                                suffix=".png", delete=False, dir="."
                            ) as tmp_img:
                                tmp_grounder_img_path = tmp_img.name
                                src_path = (
                                    "screenshot-Android.jpg"
                                    if device_type == "Android"
                                    else "screenshot-Harmony.jpg"
                                )
                                img = Image.open(src_path)
                                img.save(tmp_grounder_img_path, "PNG")
                            try:
                                grounder_response_str = local_llm_generate(
                                    grounder_prompt, tmp_grounder_img_path
                                )
                            finally:
                                if os.path.exists(tmp_grounder_img_path):
                                    os.remove(tmp_grounder_img_path)
                        else:
                            grounder_response_obj = grounder_client.chat.completions.create(
                                model=grounder_model,
                                messages=[
                                    {
                                        "role": "user",
                                        "content": [
                                            {
                                                "type": "image_url",
                                                "image_url": {
                                                    "url": f"data:image/jpeg;base64,{screenshot_resize}"
                                                },
                                            },
                                            {
                                                "type": "text",
                                                "text": grounder_prompt,
                                            },
                                        ],
                                    }
                                ],
                                temperature=0,
                            )
                            grounder_response_str = (
                                grounder_response_obj.choices[0].message.content
                            )

                        grounder_end_time = time.time()
                        print(
                            f"Grounder time taken: {grounder_end_time - grounder_start_time} seconds"
                        )
                        print(f"Grounder response: \n{grounder_response_str}")
                        grounder_response = parse_json_response(
                            grounder_response_str
                        )
                        bbox = (
                            grounder_response["bbox"]
                            if "bbox" in grounder_response
                            else None
                        )
                        bbox_2d = (
                            grounder_response["bbox_2d"]
                            if "bbox_2d" in grounder_response
                            else None
                        )
                        bbox_2d_ = grounder_response.get("bbox-2d", None)
                        bbox_2D = grounder_response.get("bbox_2D", None)
                        if bbox_2D is not None:
                            bbox = bbox_2D
                        if bbox_2d_ is not None:
                            bbox = bbox_2d_
                        if bbox_2d is not None:
                            bbox = bbox_2d

                        if use_qwen3:
                            bbox = convert_qwen3_coordinates_to_absolute(
                                bbox, img.width, img.height, is_bbox=True
                            )
                        else:
                            bbox = [
                                int(coord / factor) for coord in bbox
                            ]
                    x1, y1, x2, y2 = bbox
                    print(
                        f"Matched UI element via XML: text='{target_element}' → bounds={bbox}"
                    )
                except Exception as e:
                    print(
                        f"Failed to find element via XML, falling back to grounder (if needed): {e}"
                    )
                    raise e

                print(f"Clicking on bbox: [{x1}, {y1}, {x2}, {y2}]")
                print(f"Image size: width={img.width}, height={img.height}")
                print(f"Adjusted bbox: [{x1}, {y1}, {x2}, {y2}]")
                position_x = (x1 + x2) // 2
                position_y = (y1 + y2) // 2
                device.click(position_x, position_y)
                actions.append(
                    {
                        "type": "click",
                        "position_x": position_x,
                        "position_y": position_y,
                        "bounds": [x1, y1, x2, y2],
                        "action_index": image_index,
                    }
                )

                current_dir = os.getcwd()
                img_path = os.path.join(current_dir, current_image)
                save_path = os.path.join(data_dir, f"{image_index}_highlighted.jpg")
                img = Image.open(img_path)
                draw = ImageDraw.Draw(img)
                text = f"CLICK [{position_x}, {position_y}]"
                text = textwrap.fill(text, width=20)
                text_width, text_height = draw.textbbox((0, 0), text)[2:]
                draw.text(
                    (img.width / 2 - text_width / 2, 0), text, fill="red"
                )
                img.save(save_path)

                bounds_path = os.path.join(data_dir, f"{image_index}_bounds.jpg")
                img_bounds = Image.open(save_path)
                draw_bounds = ImageDraw.Draw(img_bounds)
                draw_bounds.rectangle([x1, y1, x2, y2], outline="red", width=5)
                img_bounds.save(bounds_path)

                click_point_path = os.path.join(
                    data_dir, f"{image_index}_click_point.jpg"
                )
                img_click = Image.open(bounds_path)
                draw_click = ImageDraw.Draw(img_click)
                radius = 15
                draw_click.ellipse(
                    [
                        position_x - radius,
                        position_y - radius,
                        position_x + radius,
                        position_y + radius,
                    ],
                    fill=(0, 255, 0),
                    outline=None,
                )
                img_click.save(click_point_path)

            else:
                # bbox_flag=False: 直接用 grounder 获取坐标
                grounder_start_time = time.time()
                if use_local_llm_grounder:
                    with tempfile.NamedTemporaryFile(
                        suffix=".png", delete=False, dir="."
                    ) as tmp_img:
                        tmp_grounder_img_path = tmp_img.name
                        src_path = (
                            "screenshot-Android.jpg"
                            if device_type == "Android"
                            else "screenshot-Harmony.jpg"
                        )
                        img = Image.open(src_path)
                        img.save(tmp_grounder_img_path, "PNG")
                    try:
                        grounder_response_str = local_llm_generate(
                            grounder_prompt, tmp_grounder_img_path
                        )
                    finally:
                        if os.path.exists(tmp_grounder_img_path):
                            os.remove(tmp_grounder_img_path)
                else:
                    grounder_response_obj = grounder_client.chat.completions.create(
                        model=grounder_model,
                        messages=[
                            {
                                "role": "user",
                                "content": [
                                    {
                                        "type": "image_url",
                                        "image_url": {
                                            "url": f"data:image/jpeg;base64,{screenshot_resize}"
                                        },
                                    },
                                    {
                                        "type": "text",
                                        "text": grounder_prompt,
                                    },
                                ],
                            }
                        ],
                        temperature=0,
                    )
                    grounder_response_str = (
                        grounder_response_obj.choices[0].message.content
                    )

                grounder_end_time = time.time()
                print(
                    f"Grounder time taken: {grounder_end_time - grounder_start_time} seconds"
                )
                print(f"Grounder response: \n{grounder_response_str}")
                grounder_response = parse_json_response(
                    grounder_response_str
                )

                coordinates = grounder_response["coordinates"]
                if use_qwen3:
                    coordinates = convert_qwen3_coordinates_to_absolute(
                        coordinates, img.width, img.height, is_bbox=False
                    )
                    x, y = coordinates
                else:
                    x, y = [int(coord / factor) for coord in coordinates]
                device.click(x, y)
                actions.append(
                    {
                        "type": "click",
                        "position_x": x,
                        "position_y": y,
                        "action_index": image_index,
                    }
                )
        elif action == "input":
            text = decider_response["parameters"]["text"]
            device.input(text)
            actions.append(
                {"type": "input", "text": text, "action_index": image_index}
            )

        elif action == "swipe":
            direction = decider_response["parameters"]["direction"]

            # 统一转大写校验,兼容 LLM 返回小写/混合大小写
            direction_upper = direction.upper()
            if direction_upper not in ["UP", "DOWN", "LEFT", "RIGHT"]:
                raise ValueError(f"Invalid swipe direction: {direction}")

            device.swipe(direction_upper.lower(), 0.6)
            actions.append(
                {
                    "type": "swipe",
                    "press_position_x": None,
                    "press_position_y": None,
                    "release_position_x": None,
                    "release_position_y": None,
                    "direction": direction_upper.lower(),
                    "action_index": image_index,
                }
            )

            create_swipe_visualization(data_dir, image_index, direction_upper.lower())
        elif action == "wait":
            print("Waiting for a while...")
            time.sleep(3)
            actions.append({"type": "wait", "action_index": image_index})
        elif action in ("stop", "terminate"):
            print(f"Task {action} requested by LLM, stopping.")
            actions.append({"type": action, "action_index": image_index})
            break
        else:
            raise ValueError(f"Unknown action: {action}")

        time.sleep(1)


def parse_planner_response(response_str: str):
    pattern = re.compile(r"```json\s*(.*?)\s*```", re.DOTALL)
    match = pattern.search(response_str)

    json_str = None
    if match:
        json_str = match.group(1)
    else:
        json_str = response_str.strip()

    try:
        data = json.loads(json_str)
        return data
    except json.JSONDecodeError as e:
        logging.error(f"解析 JSON 失败: {e}\n内容为:\n{json_str}")
        return None


def get_app_package_name(
    task_description, use_graphrag=False, device_type="Android", use_local=False
):
    """单阶段:本地检索经验,调用模型完成应用选择和任务描述生成。"""

    planner_prompt_template = '''
    ## 角色定义
你是一个任务规划专家，负责理解用户意图，选择最合适的应用，并生成一个结构化、可执行的最终任务描述。

## 已知输入
1. 原始用户任务描述："{task_description}"
2. 相关的经验/模板：
```
"{experience_content}"
```

## 可用应用列表
以下是可用的应用及其包名：
- 支付宝: com.eg.android.AlipayGphone
- 微信: com.tencent.mm
- QQ: com.tencent.mobileqq
- 新浪微博: com.sina.weibo
- 饿了么: me.ele
- 美团: com.sankuai.meituan
- bilibili: tv.danmaku.bili
- 爱奇艺: com.qiyi.video
- 腾讯视频: com.tencent.qqlive
- 优酷: com.youku.phone
- 淘宝: com.taobao.taobao
- 京东: com.jingdong.app.mall
- 携程: ctrip.android.view
- 同城: com.tongcheng.android
- 飞猪: com.taobao.trip
- 去哪儿: com.Qunar
- 华住会: com.htinns
- 知乎: com.zhihu.android
- 小红书: com.xingin.xhs
- QQ音乐: com.tencent.qqmusic
- 网易云音乐: com.netease.cloudmusic
- 酷狗音乐: com.kugou.android
- 抖音: com.ss.android.ugc.aweme
- 高德地图: com.autonavi.minimap
- 咸鱼: com.taobao.idlefish
- 华为商城：com.vmall.client
- 华为音乐: com.huawei.music
- 华为视频：com.huawei.himovie
- 华为应用市场：com.huawei.appmarket
- 拼多多：com.xunmeng.pinduoduo
- 大众点评: com.dianping.v1
- 浏览器: com.microsoft.emmx

## 任务要求
1.  **选择应用**：根据用户任务描述，从"可用应用列表"中选择最合适的应用。
2.  **生成最终任务描述**：参考最合适的"相关的经验/模板"，将用户的原始任务描述转化为一个详细、完整、结构化的任务描述。
    - **语义保持一致**：最终描述必须与用户原始意图完全相同。
    - **填充与裁剪**：
        - 如果经验/模板和原始用户任务描述不相关，根据任务对应APP的真实使用方式**简要**完善任务详细步骤
        - 仅填充模板中与用户需求直接相关的步骤,保留原始用户任务描述。
        - 处理"可选"步骤：仅当原始任务描述中显式要求时才填充 "可选"步骤且去除"可选："标识，原始任务未显示要求则移除对应步骤。
        - 模板里未被原始任务隐含或显式提及的步骤不能增加，多余步骤移除。
        - 若模板中的占位符（如 `{{城市/类型}}`）在用户描述中未提供具体信息，则移除。
    - **自然表达**：输出的描述应符合中文自然语言习惯，避免冗余。

## 输出格式
请严格按照以下JSON格式输出，不要包含任何额外内容或注释：
```json
{{
  "reasoning": "简要说明你为什么选择这个应用，以及你是如何结合用户需求和模板生成最终任务描述的。",
  "app_name": "选择的应用名称",
  "package_name": "所选应用的包名",
  "final_task_description": "最终生成的完整、结构化的任务描述文本。"
}}
```
'''

    prompt = planner_prompt_template.format(
        task_description=task_description, experience_content=""
    )

    if use_local:
        response_str = local_llm_generate(prompt)
    else:
        response_str = planner_client.chat.completions.create(
            model=planner_model,
            messages=[{"role": "user", "content": [{"type": "text", "text": prompt}]}],
        ).choices[0].message.content
    print(f"Planner 响应: \n{response_str}")
    response_json = parse_planner_response(response_str)
    if response_json is None:
        logging.error("无法解析模型响应为 JSON。")
        logging.error(f"原始响应内容: {response_str}")
        raise ValueError("无法解析模型响应为 JSON。")
    app_name = response_json.get("app_name")
    package_name = response_json.get("package_name")
    final_desc = response_json.get("final_task_description", task_description)
    return app_name, package_name, final_desc


# ============================================================================
# 主入口
# ============================================================================
if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="MobiAgent Phone Runner")
    parser.add_argument(
        "--model",
        type=str,
        default="auto",
        help="模型名称 (auto/minicpm-v-4.6/qwen3.5-0.8b/qwen3.5-2b/qwen3.5-4b/gui-owl-7b/cloud)",
    )
    parser.add_argument(
        "--model-path", type=str, default=None, help="直接指定 GGUF 模型文件路径"
    )
    parser.add_argument(
        "--mmproj-path", type=str, default=None, help="视觉模型 mmproj 文件路径"
    )
    parser.add_argument("--port", type=int, default=None, help="llama-server 端口")
    parser.add_argument(
        "--ctx-size", type=int, default=None, help="上下文长度 (默认 4096)"
    )
    parser.add_argument(
        "--list-models", action="store_true", help="列出所有可用模型并退出"
    )

    # 原有参数(保留兼容)
    parser.add_argument(
        "--service_ip",
        type=str,
        default="localhost",
        help="云端服务 IP (本地模式忽略)",
    )
    parser.add_argument("--decider_port", type=int, default=8000)
    parser.add_argument("--grounder_port", type=int, default=8001)
    parser.add_argument("--planner_port", type=int, default=8002)
    parser.add_argument("--user_profile", choices=["on", "off"], default="off")
    parser.add_argument("--use_graphrag", choices=["on", "off"], default="off")
    parser.add_argument("--clear_memory", action="store_true")
    parser.add_argument(
        "--device",
        type=str,
        default="Android",
        choices=["Android", "Harmony"],
    )
    parser.add_argument("--device_endpoint", type=str, default=None)
    # BooleanOptionalAction (Python 3.9+): 自动生成 --use_qwen3 / --no-use_qwen3
    parser.add_argument(
        "--use_qwen3",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="使用 Qwen3 坐标系 (默认开启,用 --no-use_qwen3 关闭)",
    )
    parser.add_argument("--use_experience", action="store_true", default=False)
    parser.add_argument("--use_experience_rr", action="store_true", default=False)
    parser.add_argument("--data_dir", type=str, default=None)
    parser.add_argument("--task_file", type=str, default=None)
    parser.add_argument("--local_planner", action="store_true")
    parser.add_argument("--local_grounder", action="store_true")
    parser.add_argument("--local_decider", action="store_true")
    parser.add_argument(
        "--use_grounder",
        action=argparse.BooleanOptionalAction,
        default=True,
        help="使用 grounder 模型定位 (默认开启,用 --no-use_grounder 关闭,关闭时优先 XML 匹配)",
    )

    args = parser.parse_args()

    # 列出模型
    if args.list_models:
        list_models()
        sys.exit(0)

    # 本地模式:指定了模型(auto 或具体名称)就启用所有 local 组件
    if args.model != "cloud":
        args.local_planner = True
        args.local_decider = True
        args.local_grounder = True

    # 初始化本地 LLM(如果使用本地模式)
    use_local = args.local_planner or args.local_grounder or args.local_decider
    if use_local:
        print("\n=== 初始化本地 LLM 后端 ===")
        init_local_llm(
            model_name=args.model,
            model_path=args.model_path,
            mmproj_path=args.mmproj_path,
            port=args.port,
            ctx_size=args.ctx_size,
        )

    use_experience_rr = args.use_experience_rr
    if use_experience_rr and not args.use_experience:
        logging.warning(
            "use_experience_rr is enabled but use_experience is disabled; disabling use_experience_rr."
        )
        use_experience_rr = False

    enable_user_profile = args.user_profile == "on"
    use_graphrag = args.use_graphrag == "on"

    init(
        args.service_ip,
        args.decider_port,
        args.grounder_port,
        args.planner_port,
        enable_user_profile=enable_user_profile,
        use_graphrag=use_graphrag,
        use_experience_rr=use_experience_rr,
        use_local_planner=args.local_planner,
        use_local_grounder=args.local_grounder,
        use_local_decider=args.local_decider,
    )

    device = AndroidDevice(args.device_endpoint)

    print(f"Connected to device: {args.device}")
    use_qwen3_model = args.use_qwen3
    use_experience = args.use_experience
    current_device_type = args.device
    print(f"Use Qwen3 model: {use_qwen3_model}")
    print(f"Use experience (planner task rewriting): {use_experience}")
    print(f"Device type: {current_device_type}")

    if args.data_dir:
        data_base_dir = args.data_dir
        print(f"Using custom data directory: {data_base_dir}")
    else:
        data_base_dir = os.path.join(os.path.dirname(__file__), "data")
        print(f"Using default data directory: {data_base_dir}")

    if not os.path.exists(data_base_dir):
        os.makedirs(data_base_dir)
        print(f"Created data directory: {data_base_dir}")

    if args.task_file:
        task_json_path = args.task_file
        print(f"Using custom task file: {task_json_path}")
    else:
        task_json_path = os.path.join(os.path.dirname(__file__), "task.json")

    # 加载任务列表
    if os.path.exists(task_json_path):
        try:
            with open(task_json_path, "r", encoding="utf-8") as f:
                task_data = json.load(f)
            if isinstance(task_data, list):
                task_list = task_data
            elif isinstance(task_data, dict) and "tasks" in task_data:
                task_list = task_data["tasks"]
            else:
                task_list = [str(task_data)]
            print(f"从 {task_json_path} 加载了 {len(task_list)} 个任务")
        except (json.JSONDecodeError, OSError) as e:
            logging.warning(f"读取 task.json 失败: {e}, 使用默认任务")
            task_list = ["去淘宝买草莓"]
    else:
        print(f"task.json 不存在,使用默认任务")
        task_list = ["去淘宝买草莓"]

    # 主循环(用 try/finally 确保 llama-server 被关闭)
    try:
        for task in task_list:
            existing_dirs = [
                d
                for d in os.listdir(data_base_dir)
                if os.path.isdir(os.path.join(data_base_dir, d)) and d.isdigit()
            ]
            if existing_dirs:
                data_index = max(int(d) for d in existing_dirs) + 1
            else:
                data_index = 1
            data_dir = os.path.join(data_base_dir, str(data_index))
            os.makedirs(data_dir)

            task_description = task

            print(f"Calling planner to get app_name and package_name")
            app_name, package_name, planner_task_description = get_app_package_name(
                task_description,
                use_graphrag=use_graphrag,
                device_type=current_device_type,
                use_local=args.local_planner,
            )
            print(f"Planner result - App: {app_name}, Package: {package_name}")

            if use_experience:
                print(f"Using experience: using planner-rewritten task description")
                new_task_description = planner_task_description
                print(f"New task description: {new_task_description}")
            else:
                print(f"Not using experience: using original task description")
                new_task_description = task_description

            print(f"Starting task in app: {app_name} (package: {package_name})")
            device.app_start(package_name)
            task_in_app(
                app_name,
                new_task_description,
                device,
                data_dir,
                True,
                use_qwen3_model,
                current_device_type,
                args.use_grounder,
            )
            print(f"Stopping app: {app_name} (package: {package_name})")
    finally:
        # 确保本地 LLM 服务被关闭(防止进程泄漏)
        shutdown_local_llm()
