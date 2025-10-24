"""HTTP fetching helpers."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Dict, Optional
from urllib.parse import urlparse

import requests

LOGGER = logging.getLogger(__name__)


@dataclass
class FetchResult:
    url: str
    final_url: str
    status_code: int
    content: bytes
    headers: Dict[str, str]

    @property
    def success(self) -> bool:
        return 200 <= self.status_code < 300


class PageFetcher:
    """Fetch web pages with retries and safety checks."""

    def __init__(self, headers: Optional[Dict[str, str]] = None, timeout: int = 15) -> None:
        self.session = requests.Session()
        self.headers = headers or {}
        self.timeout = timeout

    def fetch(self, url: str, allowed_domains: Optional[list[str]] = None) -> Optional[FetchResult]:
        allowed_domains = allowed_domains or []
        parsed = urlparse(url)
        if allowed_domains and parsed.netloc not in allowed_domains:
            LOGGER.warning("Skipping %s because domain not in allowed list", url)
            return None
        try:
            response = self.session.get(url, headers=self.headers, timeout=self.timeout)
            LOGGER.debug("Fetched %s with status %s", url, response.status_code)
        except requests.RequestException as exc:
            LOGGER.error("Failed to fetch %s: %s", url, exc)
            return None
        return FetchResult(
            url=url,
            final_url=str(response.url),
            status_code=response.status_code,
            content=response.content,
            headers=dict(response.headers),
        )
