<div align="center">

# catcher

**一个安卓端的信息捕获器——刷、说、转、贴，系统在后台负责存、转写、分析、找回。**

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=flat-square&logo=kotlin&logoColor=white)
![Python](https://img.shields.io/badge/Python-3776AB?style=flat-square&logo=python&logoColor=white)
![Android](https://img.shields.io/badge/Android-3DDC84?style=flat-square&logo=android&logoColor=white)
![FastAPI](https://img.shields.io/badge/FastAPI-009688?style=flat-square&logo=fastapi&logoColor=white)
![SQLite](https://img.shields.io/badge/SQLite-003B57?style=flat-square&logo=sqlite&logoColor=white)

![License](https://img.shields.io/badge/license-MIT-4B5563?style=flat-square)
![Last Commit](https://img.shields.io/github/last-commit/3431481073/catcher?style=flat-square&color=4B5563)
![Repo Size](https://img.shields.io/github/repo-size/3431481073/catcher?style=flat-square&color=4B5563)
![Status](https://img.shields.io/badge/status-active-4B5563?style=flat-square)

</div>

---

## 这个项目解决什么问题

信息来了——一段语音、一个视频、一段文字——你不想打断当下的事去整理它。

catcher 让你只管扔进来，系统在后台负责存储、转写、分析，最后通过剪贴板还给你。不确认、不选标签、不等处理。

三个入口：语音、视频、文本。
一个出口：剪贴板。

## 快速开始

**后端：**

```bash
cd backend
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

**安卓端：**

用 Android Studio 打开 android/ 目录，编译安装。

需要配置 X-Token，详见 docs/architecture.md。

## 功能

| 功能 | 说明 |
| --- | --- |
| 语音输入 | 点悬浮球说话，自动转写为文字（当前已停用） |
| 视频收藏 | 分享视频到 catcher，自动保存 |
| 文本粘贴 | 复制文本，一键保存 |
| 剪贴板输出 | 处理完成后，自动写入剪贴板 |
| 异步处理 | 耗时的转写在后台进行，接口立刻返回 |
| 断网重试 | 断网时存本地，联网后自动同步 |

## 已知限制

- 语音功能已停用（2026-09-26），保留代码与历史数据
- 视频分析本期不做，仅占位
- 不做多设备同步
- 不做增量同步
- 工作台集成尚未开始

## 许可证

MIT

本项目采用 MIT 许可证，详见 LICENSE。
