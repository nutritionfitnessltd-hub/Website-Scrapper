# Lyons Interiors

Complete static marketing website for a Morley / Leeds plastering and interior-finishing business.

## Develop

Requires Node.js 22 or later; no npm packages required.

```sh
npm run build
npm run check
python -m http.server 8080 --directory public
```

Edit `content.mjs` for business details, six services, twelve articles and FAQs. Edit `build.mjs` for page templates, `style.css` for design and `app.js` for interactions. Deploy the generated `public` directory. `vercel.json` is included.

Set `SITE_URL` to the final canonical HTTPS origin when using a custom domain. Otherwise Vercel's `VERCEL_PROJECT_PRODUCTION_URL` is used. The fallback hostname in local output is an example, not proof of a deployment.

## Enquiry flow

The browser-based quote builder creates a message. The visitor then sends it themselves by SMS or WhatsApp, or copies it. It does not submit to a database, send an email, confirm receipt, reserve a slot or take a payment. The interface states this explicitly. Telephone: +44 7306 160862. WhatsApp availability has not been independently confirmed; SMS and calling are offered alongside it.

No Supabase project was created because this release has no database requirement. A future stored-enquiry flow needs secure server-side validation, spam protection, access controls, an agreed privacy/retention policy and a verified notification destination.

## Content and evidence

Business services, area and contact information come from the supplied brief. The reported Google rating was not independently verified, so there is no star badge or fabricated testimonial. No company history, qualifications, insurance details, project count, fixed rates, fixed availability or guarantees have been invented. Reference imagery is labelled as inspiration, never as completed Lyons work. The business Instagram is linked for its own updates.

The advice library covers cost, problems, comparisons, choosing a supplier and project planning. British Gypsum and HSE references are linked in relevant technical articles. Articles are general buying guidance, not a survey or specialist building specification.

## Testing

`npm run check` audits generated pages, links, metadata and JSON-LD. `tests/browser.py` uses Playwright and Chromium. It injects the local document to allow testing in a network-restricted runtime. The recorded report covers responsive layouts, quote validation, message generation, focus restoration, navigation, advice search and no-JavaScript article access. It does not measure live Core Web Vitals, Google ranking, actual conversion rate or delivery of a customer message.

## Launch review

The business owner should confirm copy and services, supply approved real project photographs, verify the Google Business Profile link and WhatsApp availability, and connect its preferred custom domain. Do not convert stock imagery into claimed case studies. No analytics or marketing cookies are installed. No Search Console submission or search-ranking guarantee is claimed.
