"""backend.exe 进程级入口。

职责（全部在进程层，不碰 app/ 业务代码）：
- 参数：无参正常启动 / --autostart 静默 / --uninstall-autostart / --status
- 开机自启：每次正常启动幂等重写 HKCU Run\\ClipBackend = "<exe> --autostart"；
  设 CLIP_NO_AUTOSTART=1 则跳过（自启交给外部服务管理器统一负责）
- 单实例：8000 端口占用即已有实例；手动双击弹框，自启静默退出
- 日志落文件 data/backend.log（窗口态 stdout/stderr 会丢）

app/main.py 仍是纯 FastAPI 组装，不参与以上任何逻辑。
"""
import logging
import os
import socket
import sys
import urllib.request
from pathlib import Path

# 保证 dev 态 `python launcher.py` 也能 import app（frozen 态由 PyInstaller 的 PYZ 提供）
sys.path.insert(0, str(Path(__file__).resolve().parent))

import winreg

HOST = "0.0.0.0"
PORT = 8000
RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
RUN_NAME = "ClipBackend"

FROZEN = getattr(sys, "frozen", False)


def _exe_path() -> str:
    return str(Path(sys.executable).resolve())


def _msgbox(text: str, title: str = "手机剪藏") -> None:
    try:
        import ctypes
        ctypes.windll.user32.MessageBoxW(0, text, title, 0x40)  # MB_ICONINFORMATION
    except Exception:
        pass


def _autostart_muted() -> bool:
    """CLIP_NO_AUTOSTART=1 时不自注册开机自启（自启由外部服务管理器负责）。"""
    return os.environ.get("CLIP_NO_AUTOSTART", "").strip() == "1"


def _write_autostart() -> None:
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        winreg.SetValueEx(key, RUN_NAME, 0, winreg.REG_SZ, f'"{_exe_path()}" --autostart')


def _delete_autostart() -> int:
    """删除自启项。无 UI、不阻塞调用方：成功或本就不存在返回 0，失败返回 1。

    窗口态（--noconsole）下 sys.stdout/stderr 为 None，print 会静默跳过。
    """
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            winreg.DeleteValue(key, RUN_NAME)
    except FileNotFoundError:
        print("开机自启本就不存在")
        return 0
    except OSError as e:
        print(f"取消自启失败: {e}", file=sys.stderr)
        return 1
    print("已取消开机自启")
    return 0


def _health_ok() -> bool:
    try:
        with urllib.request.urlopen(
            f"http://127.0.0.1:{PORT}/api/v1/health", timeout=2
        ) as resp:
            return resp.status == 200
    except Exception:
        return False


def _already_running() -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", PORT)) == 0


def _setup_logging() -> None:
    from app import config
    config.DATA_DIR.mkdir(parents=True, exist_ok=True)
    log_path = config.DATA_DIR / "backend.log"
    # 窗口态（--noconsole）sys.stdout/stderr 为 None，uvicorn 等库会调 .isatty() 崩溃；
    # 统一重定向到日志文件，保证有流可写、崩溃 traceback 落盘。
    if sys.stderr is None:
        sys.stderr = open(str(log_path), "a", encoding="utf-8", buffering=1)
    if sys.stdout is None:
        sys.stdout = open(str(log_path), "a", encoding="utf-8", buffering=1)
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
        stream=sys.stderr,
    )


def main() -> int:
    args = sys.argv[1:]

    if "--uninstall-autostart" in args:
        return _delete_autostart()

    if "--status" in args:
        return 0 if _health_ok() else 1

    # 正常启动
    autostart = "--autostart" in args
    if _already_running():
        if not autostart:
            _msgbox("后端已在运行")
        return 0

    _setup_logging()

    if FROZEN:
        if _autostart_muted():
            logging.getLogger("launcher").info("CLIP_NO_AUTOSTART=1，跳过写自启项")
        else:
            try:
                _write_autostart()
                logging.getLogger("launcher").info("已写自启项 ClipBackend -> %s", _exe_path())
            except Exception as e:
                logging.getLogger("launcher").warning("写自启失败: %s", e)

    import uvicorn
    from app.main import app

    logging.getLogger("launcher").info("启动后端 http://%s:%d", HOST, PORT)
    uvicorn.run(app, host=HOST, port=PORT, log_config=None)  # 用上面的文件流，不再由 uvicorn 重配日志
    return 0


if __name__ == "__main__":
    sys.exit(main())
