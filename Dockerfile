# 口袋 Agent · Docker 部署（多阶段构建：前端 build + Python 运行时）

# ---- 阶段 1：构建前端 dist ----
FROM node:25-alpine AS web
WORKDIR /src
COPY agentd/web/package.json agentd/web/package-lock.json ./agentd/web/
RUN cd agentd/web && npm ci --no-audit --no-fund
COPY agentd/web/ ./agentd/web/
RUN cd agentd/web && npm run build

# ---- 阶段 2：Python 运行时 ----
FROM python:3.12-slim
ENV PYTHONUNBUFFERED=1 \
    AGENT_HOME=/data
WORKDIR /app

COPY agentd/requirements.txt ./agentd/requirements.txt
RUN pip install --no-cache-dir -r agentd/requirements.txt

COPY agentd/ ./agentd/
COPY --from=web /src/agentd/web/dist ./agentd/web/dist
# 预设配方（Jobs 面板）：agentd/main.py 按 仓库根/config/recipes.json 读取，漏了会让配方列表静默为空
COPY config/ ./config/

# 数据目录（会话/记忆/定时任务/checkpoints）
VOLUME /data
EXPOSE 8787

# 健康检查
HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD python -c "import urllib.request;urllib.request.urlopen('http://127.0.0.1:8787/api/health',timeout=3)"

# 默认 lan 模式（公网可访问，需先通过环境变量 AGENT_TOKEN 配置访问令牌）
CMD ["python", "-m", "agentd.main", "--lan", "--port", "8787"]
