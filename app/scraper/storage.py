"""Storage helpers for scraped data and assets."""

from __future__ import annotations

import csv
import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Optional

import requests

from .parser import ParsedProduct

LOGGER = logging.getLogger(__name__)


@dataclass
class ScrapeOutcome:
    sku: str
    brand: str
    website: str
    url: Optional[str]
    price: Optional[str]
    currency: Optional[str]
    availability: Optional[str]
    discovery_reason: str


class ResultWriter:
    """Write scraping results to CSV files grouped by website."""

    def __init__(self, output_dir: Path, price_column: str, availability_column: str) -> None:
        self.output_dir = output_dir
        self.price_column = price_column
        self.availability_column = availability_column
        self.output_dir.mkdir(parents=True, exist_ok=True)

    def write(self, outcomes: Iterable[ScrapeOutcome]) -> None:
        grouped: Dict[str, List[ScrapeOutcome]] = {}
        for outcome in outcomes:
            grouped.setdefault(outcome.website, []).append(outcome)
        for website, entries in grouped.items():
            file_path = self.output_dir / f"{website}_results.csv"
            LOGGER.info("Writing %s results to %s", website, file_path)
            with file_path.open("w", encoding="utf-8", newline="") as handle:
                writer = csv.DictWriter(
                    handle,
                    fieldnames=[
                        "sku",
                        "brand",
                        "website",
                        "url",
                        self.price_column,
                        "currency",
                        self.availability_column,
                        "discovery_reason",
                    ],
                )
                writer.writeheader()
                for entry in entries:
                    payload = {
                        "sku": entry.sku,
                        "brand": entry.brand,
                        "website": entry.website,
                        "url": entry.url or "",
                        self.price_column: entry.price or "",
                        "currency": entry.currency or "",
                        self.availability_column: entry.availability or "",
                        "discovery_reason": entry.discovery_reason,
                    }
                    writer.writerow(payload)


class ImageStore:
    """Store product imagery in per-brand directories."""

    def __init__(self, base_dir: Path) -> None:
        self.base_dir = base_dir
        self.base_dir.mkdir(parents=True, exist_ok=True)

    def save_first(self, product: ParsedProduct, sku: str, brand: str) -> Optional[Path]:
        if not product.image_urls:
            return None
        image_url = product.image_urls[0]
        try:
            response = requests.get(image_url, timeout=20)
            response.raise_for_status()
        except requests.RequestException as exc:
            LOGGER.warning("Unable to download %s: %s", image_url, exc)
            return None
        extension = _extension_from_headers(response.headers.get("Content-Type"))
        brand_dir = self.base_dir / _slugify(brand)
        brand_dir.mkdir(parents=True, exist_ok=True)
        file_path = brand_dir / f"{sku}{extension}"
        file_path.write_bytes(response.content)
        LOGGER.info("Saved image for %s to %s", sku, file_path)
        return file_path


def _extension_from_headers(content_type: Optional[str]) -> str:
    if not content_type:
        return ".jpg"
    if "png" in content_type:
        return ".png"
    if "gif" in content_type:
        return ".gif"
    return ".jpg"


def _slugify(value: str) -> str:
    value = value.lower().strip()
    return "-".join(part for part in re_split(r"[^a-z0-9]+", value) if part)


def re_split(pattern: str, value: str) -> List[str]:
    import re

    return re.split(pattern, value)
