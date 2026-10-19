import json,os
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
from playwright.sync_api import sync_playwright

payload={'readyForCustomers':False,'missing':['BRAND_REVIEWED','BUSINESS_CATALOG_EMPTY'],'requirements':{
 x:{'approved':False,'approvedBy':None,'approvedAt':None,'evidenceUri':None} for x in ['BRAND_REVIEWED','ADMIN_PASSWORD_ROTATED','BUSINESS_CATALOG_REVIEWED','REGULATION_PUBLISHED','PAYMENT_WEBHOOK_VERIFIED','BACKUP_RESTORE_VERIFIED']},
 'activePlans':0,'coverageLinks':0,'regulationUploaded':False,'commercialModuleEnabled':True,'paymentModuleEnabled':True}
results=[]
with sync_playwright() as pw:
 browser=pw.chromium.launch(headless=True,executable_path=os.environ.get('SYSTEM_CHROMIUM') or None,args=['--no-sandbox','--disable-dev-shm-usage'])
 for size in [(390,844),(768,1024),(1366,900)]:
  context=browser.new_context(viewport={'width':size[0],'height':size[1]})
  context.add_init_script("localStorage.setItem('nhPortalToken', 'test-only-token')")
  page=context.new_page()
  def serve(route):
   if route.request.method=='GET':route.fulfill(status=200,content_type='application/json',body=json.dumps(payload))
   else:route.fulfill(status=200,content_type='application/json',body=json.dumps(payload))
  page.route('**/api/admin/implantacao*',serve)
  errors=[]
  page.on('pageerror',lambda error:errors.append(str(error)))
  html=(ROOT/'web/admin/implantacao.html').read_text()
  html=html.replace('<script src="/admin/implantacao.js" defer></script>','')
  page.set_content(html,wait_until='domcontentloaded')
  source=(ROOT/'web/admin/implantacao.js').read_text()
  source=source.replace("const API='/api/admin/implantacao'", "const API='https://example.test/api/admin/implantacao'").replace("return localStorage.getItem('nhPortalToken')||''", "return 'test-token'")
  page.add_script_tag(content=source)
  page.locator('article.step').first.wait_for(timeout=10000)
  horizontal=page.evaluate('document.documentElement.scrollWidth > window.innerWidth')
  count=page.locator('article.step').count()
  buttons=page.locator('article.step button').count()
  assert count==6 and buttons==6,(size,count,buttons)
  assert not horizontal,(size,'overflow')
  assert not errors,(size,errors)
  page.locator('article.step button').first.click()
  assert page.locator('dialog').evaluate('(node)=>node.open')
  page.locator('#cancel').click()
  assert not page.locator('dialog').evaluate('(node)=>node.open')
  image_path=f'/tmp/tenant_wizard_{size[0]}.png'
  page.screenshot(path=image_path,full_page=True)
  results.append({'resolution':size,'cards':count,'horizontalOverflow':horizontal,'jsErrors':len(errors),'modal':'ok','screenshot':image_path})
  context.close()
 browser.close()
print(json.dumps(results,indent=2,ensure_ascii=False))
