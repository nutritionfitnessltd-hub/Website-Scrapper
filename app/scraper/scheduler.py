"""Simple weekly scheduling utilities."""

from __future__ import annotations

import logging
import threading
from datetime import datetime
from typing import Callable

LOGGER = logging.getLogger(__name__)


class WeeklyScheduler:
    """Run a callable every seven days using a background timer."""

    def __init__(self, task: Callable[[], None]) -> None:
        self.task = task
        self._timer: threading.Timer | None = None
        self._stop_event = threading.Event()

    def start(self) -> None:
        LOGGER.info("Starting weekly scheduler")
        self._schedule_next()

    def _schedule_next(self) -> None:
        if self._stop_event.is_set():
            return
        self._timer = threading.Timer(0 if self._timer is None else 7 * 24 * 3600, self._run)
        self._timer.daemon = True
        self._timer.start()

    def _run(self) -> None:
        if self._stop_event.is_set():
            return
        LOGGER.info("Running scheduled task at %s", datetime.utcnow().isoformat())
        try:
            self.task()
        finally:
            self._schedule_next()

    def stop(self) -> None:
        LOGGER.info("Stopping weekly scheduler")
        self._stop_event.set()
        if self._timer:
            self._timer.cancel()
            self._timer = None
