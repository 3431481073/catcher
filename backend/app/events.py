"""events 表写入：给外部处理程序留的"有新东西进来"信号。

外部程序**直接读数据库**（不走 API）：
    SELECT * FROM events WHERE id > <游标> ORDER BY id
自己维护游标，所以这里不需要 processed 标记。

两条约定（写在这里，因为 events 表结构要长期保持最小）：
1. ``event_type`` 是**自由 TEXT，不是固定枚举**。将来要加新类型（比如 record_transcribed、
   record_deleted）直接传新字符串即可 —— 不改表结构、不做迁移、不动已有行。
2. 事件只记"**发生了什么**"，不记内容。外部程序拿 record_id 去 records 表查正文。
   这样 events 只增不删且一直很小，正文只存一份，不存在两处不一致。

失败策略：events 是旁路通知，插不进就算了（只打 warning），绝不让主业务失败。
代价（已知并接受）：若进程正好在主业务提交后、这里插入前退出，这条事件永久缺失，
按 id 游标的外部程序不会知道那条记录 —— 是**漏**不是延迟。外部程序可定期用 records 表
兜底对账来覆盖这种极小概率的缺口。
"""
import logging

from .db import connect
from .models import _now

logger = logging.getLogger(__name__)


def emit(record_id: str, event_type: str) -> None:
    """往 events 插一条。独立连接、独立事务；失败只记日志，不抛、不阻塞主请求。

    时间戳复用 models._now()，与 records.created_at 完全同格式（ISO8601 UTC，微秒 + Z）。
    """
    try:
        with connect() as conn:
            conn.execute(
                "INSERT INTO events (record_id, event_type, created_at) VALUES (?, ?, ?)",
                (record_id, event_type, _now()),
            )
    except Exception as e:
        # 旁路通知：任何失败都不许影响主业务，只留痕
        logger.warning(
            "写 events 失败（不影响主业务）: record_id=%s event_type=%s err=%s",
            record_id, event_type, e,
        )
