"""
LLM 后端管理器
统一封装 llama-server 的启动/停止/调用,支持多模型切换。
使用 OpenAI 兼容 API 协议。
"""

import subprocess
import time
import requests
import os
import base64
import logging
import socket
from pathlib import Path
from typing import Optional, List, Dict, Any, Union

logger = logging.getLogger(__name__)

# 模型目录的绝对路径(基于本文件所在目录)
_MODELS_DIR = str(Path(__file__).parent / "models")


def _is_port_in_use(port: int, host: str = "127.0.0.1") -> bool:
    """检查端口是否被占用"""
    if not (0 < port <= 65535):
        return False
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(1)
        try:
            s.bind((host, port))
            return False
        except OSError:
            return True


class LlamaServer:
    """管理 llama-server 进程的生命周期"""

    def __init__(
        self,
        model_path: str,
        mmproj_path: Optional[str] = None,
        port: int = 8080,
        n_gpu_layers: int = 0,
        ctx_size: int = 4096,
        n_threads: Optional[int] = None,
        startup_timeout: int = 60,
        verbose: bool = False,
    ):
        self.model_path = Path(model_path)
        self.mmproj_path = Path(mmproj_path) if mmproj_path else None
        self.port = port
        self.n_gpu_layers = n_gpu_layers
        self.ctx_size = ctx_size
        self.n_threads = n_threads
        self.startup_timeout = startup_timeout
        self.verbose = verbose
        self.process: Optional[subprocess.Popen] = None
        self.base_url = f"http://127.0.0.1:{port}"

    def start(self) -> bool:
        """启动 llama-server,等待就绪后返回"""
        if not self.model_path.exists():
            raise FileNotFoundError(f"模型文件不存在: {self.model_path}")

        # 检查端口是否被占用(可能是之前的 llama-server 没关干净)
        if _is_port_in_use(self.port):
            raise RuntimeError(
                f"端口 {self.port} 已被占用。"
                f"可能是之前的 llama-server 未正常关闭,"
                f"请用 'kill $(lsof -t -i:{self.port})' 清理后重试"
            )

        cmd = [
            "llama-server",
            "-m", str(self.model_path),
            "--port", str(self.port),
            "--host", "127.0.0.1",
            "-c", str(self.ctx_size),
            "-ngl", str(self.n_gpu_layers),
        ]

        if self.mmproj_path and self.mmproj_path.exists():
            cmd.extend(["--mmproj", str(self.mmproj_path)])

        if self.n_threads:
            cmd.extend(["-t", str(self.n_threads)])

        if not self.verbose:
            cmd.append("--log-disable")

        logger.info(f"启动 llama-server: {' '.join(cmd)}")
        self.process = subprocess.Popen(
            cmd,
            stdout=subprocess.DEVNULL if not self.verbose else None,
            stderr=subprocess.DEVNULL if not self.verbose else None,
        )

        # 等待服务就绪(模型加载需要时间)
        for i in range(self.startup_timeout):
            if self.process.poll() is not None:
                raise RuntimeError(
                    f"llama-server 进程意外退出,返回码: {self.process.returncode}"
                )
            try:
                resp = requests.get(f"{self.base_url}/health", timeout=2)
                if resp.status_code == 200:
                    logger.info(f"llama-server 就绪 (端口 {self.port}, 等待 {i+1}s)")
                    return True
            except requests.ConnectionError:
                pass
            time.sleep(1)

        self.stop()
        raise RuntimeError(f"llama-server 启动超时({self.startup_timeout}s)")

    def stop(self):
        """停止服务"""
        if self.process:
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)
            self.process = None
            logger.info("llama-server 已停止")

    def is_running(self) -> bool:
        return self.process is not None and self.process.poll() is None

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb):
        self.stop()


class LLMBackend:
    """
    统一 LLM 调用接口,兼容 OpenAI API 格式。
    同时提供 chat_completion(标准)和 generate(简化)两种调用方式。
    """

    def __init__(self, server: LlamaServer):
        self.server = server
        self.base_url = server.base_url

    def chat_completion(
        self,
        messages: List[Dict[str, Any]],
        temperature: float = 0.0,
        max_tokens: int = 1024,
        response_format: Optional[Dict] = None,
        timeout: int = 180,
    ) -> str:
        """
        调用 /v1/chat/completions 接口

        Args:
            messages: OpenAI 格式消息列表
            temperature: 采样温度
            max_tokens: 最大生成 token 数
            response_format: 可选 JSON schema
            timeout: 超时秒数

        Returns:
            模型生成的文本
        """
        payload: Dict[str, Any] = {
            "model": "local-model",
            "messages": messages,
            "temperature": temperature,
            "max_tokens": max_tokens,
        }

        if response_format:
            payload["response_format"] = response_format

        resp = requests.post(
            f"{self.base_url}/v1/chat/completions",
            json=payload,
            timeout=timeout,
        )
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]

    def generate(
        self, prompt: str, image_path: Optional[str] = None
    ) -> str:
        """
        简化生成接口,兼容旧 local_llm_generate 调用方式

        Args:
            prompt: 文本 prompt
            image_path: 可选图片路径(多模态)

        Returns:
            生成的文本
        """
        if image_path:
            if not os.path.exists(image_path):
                raise FileNotFoundError(f"图片不存在: {image_path}")

            with open(image_path, "rb") as f:
                img_b64 = base64.b64encode(f.read()).decode()

            messages = [
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {
                                "url": f"data:image/jpeg;base64,{img_b64}"
                            },
                        },
                        {"type": "text", "text": prompt},
                    ],
                }
            ]
        else:
            messages = [{"role": "user", "content": prompt}]

        return self.chat_completion(messages)


# ---------------------------------------------------------------------------
# 模型注册表: 定义所有支持的模型
# ---------------------------------------------------------------------------
MODEL_REGISTRY: Dict[str, Dict[str, Any]] = {
    "minicpm-v-4.6": {
        "name": "MiniCPM-V 4.6",
        "repo": "openbmb/MiniCPM-V-4.6-gguf",
        "files": {
            "model": "MiniCPM-V-4.6-Q4_K_M.gguf",
            "mmproj": "mmproj-MiniCPM-V-4.6-F16.gguf",
        },
        "size_gb": 1.3,
        "ram_gb": 4,
        "license": "Apache-2.0",
        "source": "huggingface",
        "is_vision": True,
        "description": "面壁智能+清华,Qwen3.5-0.8B+SigLIP2,1.3B 同尺寸 SOTA,4GB RAM",
    },
    "qwen3.5-0.8b": {
        "name": "Qwen3.5-0.8B",
        "repo": "unsloth/Qwen3.5-0.8B-GGUF",
        "files": {
            "model": "Qwen3.5-0.8B-UD-Q4_K_XL.gguf",
            "mmproj": "mmproj-BF16.gguf",
        },
        "size_gb": 0.6,
        "ram_gb": 3,
        "license": "Apache-2.0",
        "source": "huggingface",
        "is_vision": True,
        "description": "Qwen3.5 最小尺寸,原生多模态,3GB RAM 极致小",
    },
    "qwen3.5-2b": {
        "name": "Qwen3.5-2B",
        "repo": "unsloth/Qwen3.5-2B-GGUF",
        "files": {
            "model": "Qwen3.5-2B-UD-Q4_K_XL.gguf",
            "mmproj": "mmproj-BF16.gguf",
        },
        "size_gb": 1.5,
        "ram_gb": 4,
        "license": "Apache-2.0",
        "source": "huggingface",
        "is_vision": True,
        "description": "Qwen3.5 原生多模态(图+视频+文本),4GB RAM",
    },
    "qwen3.5-4b": {
        "name": "Qwen3.5-4B",
        "repo": "unsloth/Qwen3.5-4B-GGUF",
        "files": {
            "model": "Qwen3.5-4B-UD-Q4_K_XL.gguf",
            "mmproj": "mmproj-BF16.gguf",
        },
        "size_gb": 2.5,
        "ram_gb": 6,
        "license": "Apache-2.0",
        "source": "huggingface",
        "is_vision": True,
        "description": "Qwen3.5 原生多模态,Unsloth 动态量化,6GB RAM",
    },
    "gui-owl-7b": {
        "name": "GUI-Owl-7B (需自转GGUF)",
        "repo": "mPLUG/GUI-Owl-7B",
        "files": {
            "model": "GUI-Owl-7B-Q4_K_M.gguf",
            "mmproj": "mmproj-F16.gguf",
        },
        "size_gb": 5.0,
        "ram_gb": 10,
        "license": "MIT",
        "source": "huggingface",
        "is_vision": True,
        "description": "通义 GUI-Owl,GUI 自动化 SOTA,需用 convert_hf_to_gguf.py 自行转换",
        "note": "此模型无官方GGUF,需从safetensors自行转换: python convert_hf_to_gguf.py mPLUG/GUI-Owl-7B --mmproj",
    },
}


def get_model_info(model_name: str) -> Dict[str, Any]:
    """获取模型信息"""
    if model_name not in MODEL_REGISTRY:
        available = ", ".join(MODEL_REGISTRY.keys())
        raise ValueError(
            f"未知模型: {model_name}。可用模型: {available}"
        )
    return MODEL_REGISTRY[model_name]


def auto_select_model(ram_gb: int) -> str:
    """根据 RAM 自动选择模型"""
    if ram_gb < 4:
        return "qwen3.5-0.8b"
    elif ram_gb < 6:
        return "minicpm-v-4.6"
    elif ram_gb < 10:
        return "qwen3.5-4b"
    else:
        return "gui-owl-7b"


def get_ram_gb() -> int:
    """获取系统 RAM(GB)"""
    try:
        with open("/proc/meminfo", "r") as f:
            for line in f:
                if line.startswith("MemTotal:"):
                    return int(line.split()[1]) // (1024 * 1024)
    except Exception:
        pass
    return 8  # 默认假设 8GB


def resolve_model_path(
    model_name: str, models_dir: str = None
) -> Dict[str, Optional[str]]:
    """
    解析模型文件路径

    Args:
        model_name: 模型名称
        models_dir: 模型目录(默认为脚本同级 models/ 目录的绝对路径)

    Returns:
        {"model": path, "mmproj": path or None}
    """
    info = get_model_info(model_name)
    if models_dir is None:
        models_dir = _MODELS_DIR
    base = Path(models_dir) / model_name

    model_file = info["files"].get("model")
    mmproj_file = info["files"].get("mmproj")

    result = {
        "model": str(base / model_file) if model_file else None,
        "mmproj": str(base / mmproj_file) if mmproj_file else None,
    }
    return result
