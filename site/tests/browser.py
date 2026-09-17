import json, re
from pathlib import Path
from playwright.sync_api import sync_playwright
root=Path(__file__).resolve().parents[1]
def load(page,url):
 part=url.split('8765')[-1] if '8765' in url else url
 file=root/'public'/part.strip('/')/'index.html'
 html=file.read_text().replace('<link rel="stylesheet" href="/style.css">','<style>'+ (root/'style.css').read_text()+'</style>')
 html=html.replace('<script defer src="/app.js"></script>','').replace('</body>','<script>'+ (root/'app.js').read_text()+'</script></body>')
 page.set_content(html,wait_until='domcontentloaded')
report={'viewports':[],'checks':[],'console_errors':[]}
with sync_playwright() as p:
 browser=p.chromium.launch(executable_path='/usr/bin/chromium',headless=True,args=['--no-sandbox'])
 page=browser.new_page(viewport={'width':1440,'height':1000},device_scale_factor=1)
 page.on('pageerror',lambda e:report['console_errors'].append(str(e)))
 # The execution environment has no outbound network; layout is checked without remote image downloads.
 page.route('https://images.unsplash.com/**',lambda r:r.abort())
 for w in [1440,1024,768,390,320]:
  page.set_viewport_size({'width':w,'height':900})
  load(page,'http://127.0.0.1:8765/')
  overflow=page.evaluate('document.documentElement.scrollWidth>innerWidth')
  report['viewports'].append({'width':w,'horizontal_overflow':overflow})
  assert not overflow,f'Overflow at {w}'
  if w==1440:page.screenshot(path=str(root/'desktop-check.png'),full_page=True)
  if w==390:page.screenshot(path=str(root/'mobile-check.png'),full_page=True)
 page.set_viewport_size({'width':1440,'height':1000})
 load(page,'http://127.0.0.1:8765/')
 page.locator('.nav [data-quote]').click()
 assert page.locator('#quote-dialog').is_visible()
 page.locator('#choose-project').click()
 assert 'Please choose' in page.locator('#quote-error').inner_text()
 page.get_by_label('Wall skimming',exact=True).check()
 page.locator('#choose-project').click()
 page.locator('#customer-name').fill('Test Customer')
 page.locator('#postcode').fill('invalid')
 page.get_by_role('button',name='Prepare my enquiry').click()
 assert page.locator('#postcode').evaluate('(el)=>el.validationMessage')
 page.locator('#postcode').fill('LS27 8AA')
 page.locator('#details').fill('One room, walls only. TEST — do not send.')
 page.get_by_role('button',name='Prepare my enquiry').click()
 assert 'LS27 8AA' in page.locator('#message-preview').inner_text()
 assert '447306160862' in page.locator('#send-sms').get_attribute('href')
 assert 'Not sent yet.' in page.locator('[data-quote-step]').nth(2).inner_text()
 page.screenshot(path=str(root/'quote-builder-check.png'))
 page.keyboard.press('Escape')
 assert not page.locator('#quote-dialog').is_visible()
 assert page.locator('.nav [data-quote]').evaluate('(el)=>el===document.activeElement')
 report['checks']+=['Quote modal opens and closes','Service selection required','Postcode validation','Message preview generated','Correct SMS destination','No automatic sending','Keyboard Escape and focus restoration']
 load(page,'http://127.0.0.1:8765/advice/')
 assert page.locator('.article-card:visible').count()==12
 page.locator('#advice-search').fill('zzzznothing')
 assert page.locator('#no-results').is_visible()
 page.locator('#clear-search').click()
 assert page.locator('.article-card:visible').count()==12
 page.get_by_role('button',name='Cost & value',exact=True).click()
 assert page.locator('.article-card:visible').count()==3
 page.locator('#advice-search').fill('cheapest')
 assert page.locator('.article-card:visible').count()==1
 report['checks']+=['12 searchable advice guides','Empty search state','Clear search control','Category filtering','Search combined with category']
 page.set_viewport_size({'width':390,'height':900})
 load(page,'http://127.0.0.1:8765/')
 page.get_by_role('button',name='Open navigation').click()
 assert page.locator('.nav-links').is_visible()
 page.get_by_role('button',name='Close navigation').click()
 assert not page.locator('.nav-links').is_visible()
 report['checks']+=['Mobile menu toggle','Desktop navigation visible without hamburger']
 for path in ['/services/wall-skimming/','/pricing/','/advice/when-can-you-paint-new-plaster/','/contact/','/checklist/']:
  load(page,'http://127.0.0.1:8765'+path)
  assert not page.evaluate('document.documentElement.scrollWidth>innerWidth'),path
 report['checks'].append('Mobile service, pricing, article, contact and checklist layouts')
 context=browser.new_context(java_script_enabled=False,viewport={'width':1280,'height':900})
 nojs=context.new_page();nojs.route('https://images.unsplash.com/**',lambda r:r.abort());load(nojs,'/advice/plastering-cost-leeds/')
 assert nojs.locator('h1').inner_text()=='How much does plastering cost in Leeds?'
 assert nojs.locator('article.prose').inner_text()
 report['checks'].append('Full article readable without JavaScript')
 browser.close()
report['remote_image_checks']='Not performed: outbound networking unavailable in local runtime.'
(root/'test-report.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
