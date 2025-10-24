# Website Scraper

This project provides a configurable scraping pipeline for monitoring competitor product pages. It imports product data from spreadsheets, automatically discovers product details from multiple e-commerce websites, and stores the outputs (including fallback image downloads) in structured folders per brand.

## Features

- Load product metadata from CSV or XLSX spreadsheets.
- Automatically discover product URLs from a competitor homepage when links are not provided.
- Parse JSON-LD, OpenGraph, and common HTML price markers to capture price, currency, and availability.
- Download the first product image whenever a product is marked as `not_found` in the master sheet or when pricing is missing, storing it under `images/<brand>/<sku>.ext` (variable SKUs automatically drop the `-p` suffix).
- Output one CSV per competitor website so you can quickly compare prices "tab by tab".
- Optional weekly scheduling mode that reruns the scrape without manual intervention.

## Getting Started

1. Create and activate a Python 3.11+ environment.
2. Install dependencies:

   ```bash
   pip install -r requirements.txt
   ```

3. Prepare a spreadsheet with the following columns (additional columns are preserved as metadata):

   | Column        | Description                                                      |
   | ------------- | ---------------------------------------------------------------- |
   | `sku`         | Product SKU from your catalog.                                   |
   | `product_name`| Friendly product name.                                          |
   | `brand`       | Brand identifier used to group downloaded imagery.              |
   | `website`     | Identifier of the competitor website (e.g., `example.com`).     |
   | `product_url` | Direct product URL (leave blank to auto-discover from homepage).|
   | `status`      | Use `not_found` to trigger image capture when prices are missing.|

4. (Optional) Create a JSON file describing website metadata, e.g.:

   ```json
   [
     {
       "name": "example.com",
       "homepage": "https://www.example.com",
       "allowed_domains": ["www.example.com", "example.com"]
     }
   ]
   ```

5. Run the scraper once:

   ```bash
   python -m app.scraper.cli data/products.xlsx --websites config/websites.json
   ```

6. Or run it continuously every seven days:

   ```bash
   python -m app.scraper.cli data/products.xlsx --websites config/websites.json --schedule
   ```

Results are written to `output/<website>_results.csv`. Product images are saved under `output/images/<brand>/` with filenames matching the SKU (variable SKUs automatically trim `-p`).

