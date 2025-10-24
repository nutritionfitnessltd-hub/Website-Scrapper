"""Command line interface for the scraper application."""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path
from typing import List, Optional

from .config import ScraperConfig, WebsiteConfig
from .discovery import UrlDiscoverer
from .fetcher import PageFetcher
from .pipeline import PipelineDependencies, ScrapePipeline
from .scheduler import WeeklyScheduler
from .spreadsheet import SpreadsheetLoader
from .storage import ImageStore, ResultWriter

LOGGER = logging.getLogger(__name__)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Competitor website scraping toolkit")
    parser.add_argument("spreadsheet", type=Path, help="Path to the master spreadsheet (CSV or XLSX)")
    parser.add_argument(
        "--websites",
        type=Path,
        required=False,
        help="Optional JSON file describing competitor websites",
    )
    parser.add_argument(
        "--website",
        type=str,
        help="Only scrape the specified website name",
    )
    parser.add_argument(
        "--schedule",
        action="store_true",
        help="Run continuously and repeat the scrape every seven days",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=Path("output"),
        help="Directory where result CSV files will be stored",
    )
    parser.add_argument(
        "--images",
        type=Path,
        default=Path("output") / "images",
        help="Directory where product images will be stored",
    )
    parser.add_argument("--log-level", default="INFO", choices=["DEBUG", "INFO", "WARNING", "ERROR"])
    return parser


def load_config(args: argparse.Namespace) -> ScraperConfig:
    config = ScraperConfig(output_dir=args.output, image_dir=args.images)
    if args.websites and args.websites.exists():
        data = json.loads(args.websites.read_text())
        for entry in data:
            config.add_website(WebsiteConfig(**entry))
    else:
        LOGGER.warning("No website configuration file provided; using spreadsheet entries only")
    return config


def run_once(args: argparse.Namespace, config: ScraperConfig) -> None:
    loader = SpreadsheetLoader(expected_columns=config.spreadsheet_columns)
    rows = loader.load(args.spreadsheet)
    config.ensure_directories()
    for website in rows:
        if website.website and website.website not in config.websites:
            config.add_website(WebsiteConfig(name=website.website, homepage=f"https://{website.website}"))
    pipeline = build_pipeline(config)
    pipeline.run(rows, website_filter=args.website)


def build_pipeline(config: ScraperConfig) -> ScrapePipeline:
    fetcher = PageFetcher()
    discoverer = UrlDiscoverer(fetcher)
    image_store = ImageStore(config.image_dir)
    writer = ResultWriter(config.output_dir, config.price_column_name, config.availability_column_name)
    dependencies = PipelineDependencies(
        config=config,
        fetcher=fetcher,
        discoverer=discoverer,
        image_store=image_store,
        writer=writer,
    )
    return ScrapePipeline(dependencies)


def main(argv: Optional[List[str]] = None) -> int:
    argv = argv or sys.argv[1:]
    parser = build_parser()
    args = parser.parse_args(argv)
    logging.basicConfig(level=getattr(logging, args.log_level))
    config = load_config(args)

    def task() -> None:
        run_once(args, config)

    if args.schedule:
        scheduler = WeeklyScheduler(task)
        scheduler.start()
        try:
            import time
            while True:
                time.sleep(1)
        except KeyboardInterrupt:
            scheduler.stop()
    else:
        task()
    return 0


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
