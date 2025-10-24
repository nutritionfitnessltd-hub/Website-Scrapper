"""Scraping pipeline orchestrating the components."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Iterable, List

from .config import ScraperConfig, WebsiteConfig
from .discovery import UrlDiscoverer
from .fetcher import PageFetcher
from .parser import parse_product
from .spreadsheet import SpreadsheetRow
from .storage import ImageStore, ResultWriter, ScrapeOutcome

LOGGER = logging.getLogger(__name__)


@dataclass
class PipelineDependencies:
    config: ScraperConfig
    fetcher: PageFetcher
    discoverer: UrlDiscoverer
    image_store: ImageStore
    writer: ResultWriter


class ScrapePipeline:
    def __init__(self, dependencies: PipelineDependencies) -> None:
        self.config = dependencies.config
        self.fetcher = dependencies.fetcher
        self.discoverer = dependencies.discoverer
        self.image_store = dependencies.image_store
        self.writer = dependencies.writer

    def run(self, rows: Iterable[SpreadsheetRow], website_filter: str | None = None) -> List[ScrapeOutcome]:
        outcomes: List[ScrapeOutcome] = []
        for row in rows:
            if website_filter and row.website.lower() != website_filter.lower():
                continue
            site_config = self.config.websites.get(row.website)
            if not site_config:
                LOGGER.warning("No configuration for website %s", row.website)
                continue
            outcome = self._process_row(row, site_config)
            if outcome:
                outcomes.append(outcome)
        if outcomes:
            self.writer.write(outcomes)
        return outcomes

    def _process_row(self, row: SpreadsheetRow, site_config: WebsiteConfig) -> ScrapeOutcome | None:
        product_url = row.product_url
        discovery_reason = "provided"
        if not product_url:
            hints = [row.sku, row.product_name]
            discovery = self.discoverer.find_product_url(
                site_config.homepage, hints, allowed_domains=site_config.allowed_domains
            )
            product_url = discovery.url
            discovery_reason = discovery.reason
        else:
            discovery_reason = "spreadsheet"
        if not product_url:
            LOGGER.info("No product URL found for %s (%s)", row.sku, row.website)
            return ScrapeOutcome(
                sku=row.sku,
                brand=row.brand,
                website=row.website,
                url=None,
                price=None,
                currency=None,
                availability=None,
                discovery_reason=discovery_reason,
            )
        result = self.fetcher.fetch(product_url, allowed_domains=site_config.allowed_domains)
        if not result or not result.success:
            LOGGER.warning("Failed to fetch %s for %s", product_url, row.sku)
            return ScrapeOutcome(
                sku=row.sku,
                brand=row.brand,
                website=row.website,
                url=product_url,
                price=None,
                currency=None,
                availability=None,
                discovery_reason="fetch_failed",
            )
        product = parse_product(result.content, result.final_url)
        if row.status.lower() == "not_found" or not product.price:
            self.image_store.save_first(product, row.normalized_sku, row.brand)
        return ScrapeOutcome(
            sku=row.sku,
            brand=row.brand,
            website=row.website,
            url=result.final_url,
            price=product.price,
            currency=product.currency,
            availability=product.availability,
            discovery_reason=discovery_reason,
        )
