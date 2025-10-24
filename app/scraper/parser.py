"""HTML parsing utilities to extract product data."""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from typing import Dict, List, Optional
from urllib.parse import urljoin

from bs4 import BeautifulSoup

LOGGER = logging.getLogger(__name__)

PRICE_PATTERN = re.compile(r"(?:\$|£|€)\s?([0-9]+(?:\.[0-9]{2})?)")


@dataclass
class ParsedProduct:
    name: Optional[str] = None
    price: Optional[str] = None
    currency: Optional[str] = None
    availability: Optional[str] = None
    image_urls: List[str] = field(default_factory=list)
    raw: Dict[str, str] = field(default_factory=dict)

    def merge(self, other: "ParsedProduct") -> None:
        if not self.name and other.name:
            self.name = other.name
        if not self.price and other.price:
            self.price = other.price
        if not self.currency and other.currency:
            self.currency = other.currency
        if not self.availability and other.availability:
            self.availability = other.availability
        if other.image_urls:
            combined = list(dict.fromkeys(self.image_urls + other.image_urls))
            self.image_urls = combined
        self.raw.update({k: v for k, v in other.raw.items() if v})


def parse_product(html: bytes, base_url: str) -> ParsedProduct:
    soup = BeautifulSoup(html, "html.parser")
    result = ParsedProduct()
    result.merge(_parse_json_ld(soup))
    result.merge(_parse_microdata(soup))
    result.merge(_parse_open_graph(soup))
    result.merge(_parse_price_candidates(soup))
    result.image_urls = [urljoin(base_url, img) for img in result.image_urls]
    return result


def _parse_json_ld(soup: BeautifulSoup) -> ParsedProduct:
    result = ParsedProduct()
    for script in soup.find_all("script", type="application/ld+json"):
        try:
            data = json.loads(script.string or "{}")
        except json.JSONDecodeError:
            continue
        candidates = data if isinstance(data, list) else [data]
        for candidate in candidates:
            if not isinstance(candidate, dict):
                continue
            if candidate.get("@type") in {"Product", ["Product"]}:
                result.name = result.name or candidate.get("name")
                offers = candidate.get("offers")
                if isinstance(offers, dict):
                    result.price = result.price or str(offers.get("price"))
                    result.currency = result.currency or offers.get("priceCurrency")
                    result.availability = result.availability or offers.get("availability")
                images = candidate.get("image")
                if isinstance(images, list):
                    result.image_urls.extend([str(img) for img in images if isinstance(img, str)])
                elif isinstance(images, str):
                    result.image_urls.append(images)
    return result


def _parse_microdata(soup: BeautifulSoup) -> ParsedProduct:
    result = ParsedProduct()
    product_scope = soup.find(attrs={"itemtype": re.compile("Product", re.I)})
    if not product_scope:
        return result
    if not result.name:
        name_tag = product_scope.find(attrs={"itemprop": "name"})
        if name_tag:
            result.name = name_tag.get_text(strip=True)
    price_tag = product_scope.find(attrs={"itemprop": "price"})
    if price_tag and not result.price:
        result.price = price_tag.get("content") or price_tag.get_text(strip=True)
    currency_tag = product_scope.find(attrs={"itemprop": "priceCurrency"})
    if currency_tag and not result.currency:
        result.currency = currency_tag.get("content") or currency_tag.get_text(strip=True)
    availability_tag = product_scope.find(attrs={"itemprop": "availability"})
    if availability_tag and not result.availability:
        result.availability = availability_tag.get("content") or availability_tag.get_text(strip=True)
    for tag in product_scope.find_all(attrs={"itemprop": "image"}):
        src = tag.get("content") or tag.get("src")
        if src:
            result.image_urls.append(src)
    return result


def _parse_open_graph(soup: BeautifulSoup) -> ParsedProduct:
    result = ParsedProduct()
    for meta in soup.find_all("meta"):
        property_attr = meta.get("property") or meta.get("name")
        content = meta.get("content")
        if not property_attr or not content:
            continue
        if property_attr == "og:title" and not result.name:
            result.name = content
        elif property_attr in {"product:price", "product:price:amount"} and not result.price:
            result.price = content
        elif property_attr == "product:price:currency" and not result.currency:
            result.currency = content
        elif property_attr == "og:image":
            result.image_urls.append(content)
    return result


def _parse_price_candidates(soup: BeautifulSoup) -> ParsedProduct:
    result = ParsedProduct()
    price_candidates: List[str] = []
    for selector in ["span", "div"]:
        for tag in soup.find_all(selector, class_=re.compile("price|amount", re.I)):
            price_candidates.append(tag.get_text(strip=True))
    if not price_candidates:
        text = soup.get_text(" ", strip=True)
        price_candidates.extend(PRICE_PATTERN.findall(text))
    for candidate in price_candidates:
        match = PRICE_PATTERN.search(candidate)
        if match:
            amount = match.group(1)
            currency_match = re.search(r"(\$|£|€)", candidate)
            currency = currency_match.group(1) if currency_match else None
            result.price = result.price or amount
            result.currency = result.currency or currency
            break
    if not result.image_urls:
        for tag in soup.find_all("img"):
            src = tag.get("data-src") or tag.get("src")
            if src:
                result.image_urls.append(src)
                break
    return result
