"""Heuristics to discover product URLs on competitor websites."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Iterable, Optional
from urllib.parse import urljoin, urlparse

from bs4 import BeautifulSoup

from .fetcher import PageFetcher

LOGGER = logging.getLogger(__name__)


@dataclass
class DiscoveryResult:
    url: Optional[str]
    reason: str


class UrlDiscoverer:
    """Find candidate product URLs for a spreadsheet row."""

    def __init__(self, fetcher: PageFetcher) -> None:
        self.fetcher = fetcher

    def find_product_url(
        self,
        homepage: str,
        hints: Iterable[str],
        allowed_domains: Optional[list[str]] = None,
    ) -> DiscoveryResult:
        response = self.fetcher.fetch(homepage, allowed_domains=allowed_domains)
        if not response or not response.success:
            return DiscoveryResult(url=None, reason="homepage_unreachable")

        soup = BeautifulSoup(response.content, "html.parser")
        candidates = []
        normalized_hints = [hint.lower() for hint in hints if hint]
        for anchor in soup.find_all("a", href=True):
            href = anchor.get("href")
            text = anchor.get_text(" ", strip=True)
            candidate_url = urljoin(response.final_url, href)
            if _matches(candidate_url, text, normalized_hints):
                parsed = urlparse(candidate_url)
                if parsed.scheme in {"http", "https"}:
                    candidates.append(candidate_url)
        if not candidates:
            return DiscoveryResult(url=None, reason="no_match")
        unique = list(dict.fromkeys(candidates))
        LOGGER.debug("Discovered %s candidate URLs", len(unique))
        return DiscoveryResult(url=unique[0], reason="match")


def _matches(url: str, text: str, hints: Iterable[str]) -> bool:
    text_lower = text.lower()
    return any(hint.lower() in url.lower() or hint.lower() in text_lower for hint in hints if hint)
