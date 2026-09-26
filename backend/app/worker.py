"""进程内 asyncio worker：DB 轮询，转写串行（Semaphore(1)），启动自愈。"""
import asyncio
import logging
import time

from . import config, models, transcribe

logger = logging.getLogger("worker")


class Worker:
    def __init__(self):
        self.semaphore = asyncio.Semaphore(1)
        self.running = False
        self._task = None

    async def start(self):
        # 自愈：进程重启把遗留 processing 重置为 pending（不悬挂）
        n = models.reset_processing_to_pending()
        if n:
            logger.info("自愈：重置 %d 个 processing 任务", n)
        self.running = True
        self._task = asyncio.create_task(self._loop())

    async def stop(self):
        self.running = False
        if self._task:
            self._task.cancel()
            try:
                await self._task
            except asyncio.CancelledError:
                pass

    async def _loop(self):
        while self.running:
            # 语音停用时不再领取 transcribe 任务。当前没有生产 pending 转写任务的路径
            # （/transcribe 已拦），这里纯粹是防御：遗留/回归的任务也不会被转写。
            task = models.claim_next_pending_task(
                exclude_types=() if config.VOICE_ENABLED else ("transcribe",)
            )
            if task is None:
                await asyncio.sleep(1.0)
                continue
            async with self.semaphore:
                await self._process(task)

    async def _process(self, task):
        task_id = task["id"]
        record_id = task["record_id"]
        try:
            record = models.get_record_by_id(record_id)
            if not record or not record["raw_audio"]:
                raise RuntimeError("记录不存在或缺少音频")
            model = await transcribe.get_model()
            wav_path = str(config.WAV_DIR / f"{record_id}.wav")
            t0 = time.monotonic()
            # 重活（FFmpeg / whisper）放线程，不阻塞事件循环
            await asyncio.to_thread(transcribe.convert_to_wav, record["raw_audio"], wav_path)
            text = await asyncio.to_thread(transcribe.transcribe_wav, model, wav_path)
            elapsed = time.monotonic() - t0
            models.update_record(record_id, content=text, status="done", wav_audio=wav_path)
            models.set_task_status(task_id, "done", result=text)
            logger.info("任务完成 %s，转写耗时 %.2fs", task_id, elapsed)
        except Exception as e:
            err = str(e)
            models.update_record(record_id, status="failed", error=err)
            models.set_task_status(task_id, "failed", error=err)
            logger.exception("任务失败 %s", task_id)


worker = Worker()
