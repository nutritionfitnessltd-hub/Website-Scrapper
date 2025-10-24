"""Configuration dataclasses for the scraper application."""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, Iterable, List, Optional


@dataclass
class WebsiteConfig:
    """Configuration for a single competitor website."""

    name: str
    homepage: str
    discovery_hint_columns: List[str] = field(default_factory=lambda: ["product_url", "sku", "product_name"])
    allowed_domains: Optional[List[str]] = None
    request_headers: Dict[str, str] = field(
        default_factory=lambda: {
            "User-Agent": (
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                "(KHTML, like Gecko) Chrome/120.0 Safari/537.36"
            )
        }
    )

    def normalize(self) -> None:
        """Normalize optional values."""

        if self.allowed_domains is None:
            self.allowed_domains = []


@dataclass
class ScraperConfig:
    """Top-level configuration for the scraping pipeline."""

    output_dir: Path = Path("output")
    image_dir: Path = Path("output") / "images"
    websites: Dict[str, WebsiteConfig] = field(default_factory=dict)
    spreadsheet_columns: List[str] = field(
        default_factory=lambda: ["sku", "product_name", "brand", "website", "product_url", "status"]
    )
    price_column_name: str = "price"
    availability_column_name: str = "availability"

    def ensure_directories(self) -> None:
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.image_dir.mkdir(parents=True, exist_ok=True)

    def add_website(self, config: WebsiteConfig) -> None:
        config.normalize()
        self.websites[config.name] = config

    @classmethod
    def from_iterable(cls, configs: Iterable[WebsiteConfig], **kwargs) -> "ScraperConfig":
        instance = cls(**kwargs)
        for config in configs:
            instance.add_website(config)
        return instance
