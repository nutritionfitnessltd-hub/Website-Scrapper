# Lyons Interiors — website

A complete, dependency-free static website for residential and commercial plastering in Morley, Leeds and West Yorkshire.

**This is the independent `lyons-interiors` branch. Do not merge it into the Website-Scrapper main branch.** No Nutrition.Fitness, Love Stories Events or Castle Grove project has been changed. Repository creation was not exposed by the connected GitHub interface, so a separate branch is used for this standalone project.

## Build and deploy

Requires Node.js 22. No npm packages or database keys are needed.

```sh
npm run build
```

The generated website is in `public/`; `vercel.json` contains the build and security configuration. The build performs a SHA-256 integrity check and audits all generated internal links, page metadata and JSON-LD.

## Editable original source

The complete original source is stored losslessly in the four `source/part*.txt` files (Brotli-compressed JSON, Base64 transport). No code or article is omitted. This transport format keeps the connected publishing transfer compact.

```sh
npm run unpack
cd editable-source
npm run build
npm run check
```

The restored source contains `content.mjs` (business data, services, 12 complete articles and FAQs), `build.mjs` (all page templates and metadata), `style.css`, `app.js`, configuration, the browser tests, the test report and a full development README. Edit and build in that restored folder; changes there do not automatically change the immutable transport snapshot. The unpack command refuses to overwrite an existing editable-source folder. For regular development, commit those restored files to a dedicated repository.

## What is included

31 HTML pages: homepage, six service pages, service directory, pricing guidance, searchable advice library and twelve full articles, business/about/work/coverage/contact pages, a printable preparation checklist, three utility notices and a custom 404. Twenty-seven pages are included in the XML sitemap.

Customer-led copy focuses on a better finished room, common concerns, a clear three-step plan and prominent quote requests. A responsive modal creates a structured enquiry to send by text or WhatsApp, with click-to-call alternatives. Search and category filters work entirely in the browser. Every article is present in its own static HTML and remains readable without JavaScript.

## Enquiries and data

The visitor must press Send in their messaging app. This is not a background form submission, an email delivery system or a confirmed booking. The website does not store enquiry details. Supabase was not needed for this release. WhatsApp availability should be confirmed by the business; SMS and phone remain alternative routes.

## Evidence and launch review

Business contact and service information comes from the supplied brief. The reported Google rating could not be independently verified and is not displayed as a verified badge. No testimonials, years of experience, accreditations, project counts, fixed rates or guarantees have been invented. Interior images are marked as inspiration, not claimed as completed work. Actual project updates link to the supplied Instagram profile.

Before paid promotion, the business owner should approve copy, provide real project photographs and a confirmed review-profile link, confirm messaging arrangements, and connect the desired domain. Set SITE_URL when using a custom domain; otherwise the generator uses VERCEL_PROJECT_PRODUCTION_URL. The local fallback hostname is not proof of a deployment. No conversion rate, Google ranking, independent accessibility certification or live Lighthouse score is claimed.

## Local test result

31 HTML pages and 995 internal link references passed the static audit. Browser interaction checks covered quote validation and preview, correct SMS destination, keyboard dismissal and focus restoration, advice filtering, mobile navigation and no-JavaScript article access. Homepage layouts were checked at 1440, 1024, 768, 390 and 320 pixels. Remote images were not downloaded by the network-restricted local browser.
