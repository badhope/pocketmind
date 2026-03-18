# 🎓 学生信息系统

一个功能完善的Web端学生信息管理系统，用于管理学生资料、考试成绩、名册录入以及确认与统计等事务。

![Version](https://img.shields.io/badge/version-1.0.0-blue)
![Node.js](https://img.shields.io/badge/Node.js-14+-green)
![License](https://img.shields.io/badge/license-MIT-yellow)

---

## 📋 项目概述

### 核心功能

本系统旨在为社团部长等学生管理人员提供一个便捷的工具，用于处理日常工作中的学生信息管理需求。

| 功能模块 | 说明 |
|---------|------|
| 👥 基础信息管理 | 管理学生的学号、姓名、性别、班级、联系方式等基本信息 |
| 📝 考试/成绩录入 | 录入各种考试（期末、月考、体育测试、四六级等）的成绩 |
| 📋 名册/点名录入 | 管理四六级报名、比赛报名、活动参加、签到表等各种名册 |
| ✅ 确认与统计 | 创建活动确认、表单提交确认，统计确认状态 |
| 💾 数据备份与恢复 | 支持手动备份和恢复数据，防止数据丢失 |

### 价值定位

- **简单易用**：无需专业培训即可上手操作
- **功能全面**：覆盖学生管理的各种场景
- **数据安全**：支持数据导入导出和备份恢复
- **界面美观**：现代化UI设计，交互友好

---

## 🏗️ 技术架构

### 系统架构图

```
┌─────────────────────────────────────────────────────────────┐
│                      前端 (浏览器)                           │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              HTML + CSS + JavaScript                │   │
│  │  • 响应式布局  • Font Awesome图标  • 动画效果      │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼ HTTP API
┌─────────────────────────────────────────────────────────────┐
│                    后端 (Node.js + Express)                 │
│  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐       │
│  │ 数据管理API  │ │ 文件导入/导出 │ │ 备份/恢复API │       │
│  └──────────────┘ └──────────────┘ └──────────────┘       │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                      数据存储 (JSON文件)                     │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐           │
│  │students │ │ exams  │ │rosters  │ │confirms │           │
│  │.json   │ │ .json  │ │ .json   │ │  .json  │           │
│  └─────────┘ └─────────┘ └─────────┘ └─────────┘           │
│                      backups/ (备份文件)                     │
└─────────────────────────────────────────────────────────────┘
```

### 技术栈

| 层次 | 技术选型 | 说明 |
|------|----------|------|
| 前端框架 | 原生 HTML/CSS/JS | 无需构建工具，直接运行 |
| 后端框架 | Express.js | 轻量级Node.js Web框架 |
| 文件处理 | xlsx.js | Excel文件解析与生成 |
| 文件上传 | Multer | Node.js文件上传中间件 |
| 数据存储 | JSON文件 | 本地文件系统存储 |
| 图标库 | Font Awesome 6.4 | Web图标库 |

---

## 🚀 环境配置与安装

### 环境要求

- **操作系统**：Windows 7+ / macOS / Linux
- **Node.js**：14.0.0 或更高版本
- **npm**：6.0.0 或更高版本

### 安装步骤

#### 1. 克隆或下载项目

```bash
# 如果使用Git
git clone <repository-url>

# 或者直接解压下载的ZIP文件
cd Student-information
```

#### 2. 安装依赖

```bash
npm install
```

#### 3. 启动系统

```bash
npm start
```

#### 4. 访问系统

打开浏览器访问：**http://localhost:3000**

---

## 📖 详细使用指南

### 功能模块说明

#### 1. 基础信息管理

用于管理学生的基本信息，包括：
- 学号（必填）
- 姓名（必填）
- 性别
- 班级
- 联系方式

**操作流程：**
1. 点击"添加学生"按钮
2. 填写学生信息表单
3. 点击"保存"按钮

#### 2. 考试/成绩录入

用于记录各类考试的成绩，包括：
- 期末考试
- 期中考试
- 月考
- 体育测试
- 四六级考试
- 其他

**支持的功能：**
- 按考试类型筛选
- 按学生姓名搜索
- 批量导入Excel

#### 3. 名册/点名录入

用于管理各种名册，如：
- 四六级报名名单
- 比赛报名名单
- 活动参加名单
- 签到表

**支持的功能：**
- 按名册类型筛选
- 按名称或学生搜索
- 批量导入Excel

#### 4. 确认与统计

用于创建和管理各类确认事务：
- 活动确认
- 表单提交确认
- 报名确认

**操作流程：**
1. 点击"创建确认"按钮
2. 填写确认名称和类型
3. 选择需要确认的学生
4. 保存后可查看确认状态统计

### 数据导入说明

#### Excel文件格式要求

Excel文件应包含以下列（根据数据类型）：

**学生基础信息：**
| 学号 | 姓名 | 性别 | 班级 | 联系方式 |
|------|------|------|------|----------|
| 2024001 | 张三 | 男 | 计算机2401 | 13800001001 |

**考试记录：**
| 考试名称 | 考试类型 | 学号 | 姓名 | 科目 | 成绩 | 考试日期 |
|----------|----------|------|------|------|------|----------|
| 期末考试 | 期末考试 | 2024001 | 张三 | 高数 | 92 | 2025-01-15 |

**名册记录：**
| 名册名称 | 名册类型 | 学号 | 姓名 | 详细信息 |
|----------|----------|------|------|----------|
| 四六级报名 | 四六级报名 | 2024001 | 张三 | 英语六级 |

#### 导入步骤

1. 点击对应模块的"导入"按钮
2. 点击文件上传区域或拖拽Excel文件
3. 系统会显示数据预览
4. 确认无误后点击"确认导入"

### 数据导出说明

每个模块都支持导出为Excel文件：
1. 点击对应模块的"导出"按钮
2. 系统会自动下载Excel文件
3. 文件名格式：`模块名_日期.xlsx`

### 数据备份与恢复

#### 创建备份

1. 点击右上角"数据备份"按钮
2. 点击"创建备份"按钮
3. 备份文件将保存在 `backups` 目录

#### 恢复数据

1. 点击右上角"数据备份"按钮
2. 在备份历史中找到需要的备份
3. 点击"恢复"按钮
4. 确认后数据将被覆盖

#### 外部文件恢复

也可以导入外部的JSON备份文件：
1. 点击"恢复数据"按钮
2. 选择JSON格式的备份文件
3. 系统会将数据合并到现有数据中

---

## ⚙️ 配置参数说明

### 默认配置

| 参数 | 默认值 | 说明 |
|------|--------|------|
| PORT | 3000 | 服务器监听端口 |
| DATA_DIR | ./data | 数据存储目录 |
| BACKUP_DIR | ./backups | 备份文件目录 |
| MAX_FILE_SIZE | 5MB | 最大上传文件大小 |

### 自定义配置

可以通过环境变量修改配置：

```bash
# 修改端口
PORT=8080 npm start

# 修改数据目录
DATA_DIR=/path/to/data npm start
```

---

## 🤝 贡献指南

### 代码规范

- 使用有意义的变量和函数命名
- 保持代码缩进一致（4空格）
- 添加必要的错误处理
- 遵循RESTful API设计原则

### 提交流程

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/xxx`)
3. 提交更改 (`git commit -m 'Add xxx'`)
4. 推送分支 (`git push origin feature/xxx`)
5. 创建 Pull Request

---

## 📄 许可证

本项目基于 **MIT 许可证** 开源。

```
MIT License

Copyright (c) 2025 学生信息系统

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## ❓ 常见问题解答 (FAQ)

### Q1: 如何修改服务器端口？

可以通过环境变量修改：`PORT=8080 npm start`

### Q2: 数据存储在哪里？

数据存储在项目目录下的 `data` 文件夹中，每个模块对应一个JSON文件。

### Q3: 如何导入Excel文件？

点击对应模块的"导入"按钮，选择Excel文件（.xlsx或.xls格式）即可。

### Q4: 如何备份数据？

点击右上角的"数据备份"按钮，可以创建备份或恢复数据。

### Q5: 导入Excel时报错怎么办？

1. 检查Excel文件格式是否正确
2. 确保表头与系统要求一致
3. 检查是否有特殊字符
4. 文件大小不能超过5MB

### Q6: 系统无法启动怎么办？

1. 检查Node.js是否正确安装：`node -v`
2. 检查端口是否被占用：`netstat -ano | findstr :3000`
3. 检查依赖是否安装成功：`npm install`
4. 查看控制台错误信息

### Q7: 如何卸载/删除系统？

1. 停止服务器（Ctrl+C）
2. 删除项目文件夹
3. 如果需要，可以删除data和backups文件夹

### Q8: 支持移动端使用吗？

系统采用响应式设计，支持在手机和平板上使用。

---

## 📞 技术支持

如果遇到问题，请检查：

1. [常见问题解答](#常见问题解答-faq)
2. 控制台错误信息
3. Node.js版本是否满足要求

---

**祝您使用愉快！** 🎉
