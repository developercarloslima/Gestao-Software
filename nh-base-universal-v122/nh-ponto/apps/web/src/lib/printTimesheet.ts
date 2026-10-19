function escapeHtml(value:string){
 return value.replace(/[&<>"]/g,(char)=>({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;' }[char]??char));
}

function collectStyles(){
 return Array.from(document.head.querySelectorAll('link[rel="stylesheet"], style')).map((node)=>{
  if(node instanceof HTMLLinkElement){
   return `<link rel="stylesheet" href="${escapeHtml(node.href)}">`;
  }
  return node.outerHTML;
 }).join('\n');
}

export function printTimesheetOnly(containerId:string,title:string){
 const container=document.getElementById(containerId);
 const report=container?.querySelector<HTMLElement>('.timesheet-document');
 if(!report)throw new Error('Espelho de ponto não encontrado para impressão.');

 // O clique do usuário abre uma janela exclusiva para o documento. Assim Safari/iOS,
 // Chrome/Android e desktop nunca imprimem o painel administrativo ao redor do espelho.
 const popup=window.open('','_blank');
 if(!popup)throw new Error('O navegador bloqueou a janela de impressão. Permita pop-ups para este site e tente novamente.');

 const styles=collectStyles();
 const safeTitle=escapeHtml(title);
 const baseHref=escapeHtml(document.baseURI);
 popup.document.open();
 popup.document.write(`<!doctype html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<base href="${baseHref}">
<title>${safeTitle}</title>
${styles}
<style>
 html,body{margin:0!important;padding:0!important;background:#fff!important;min-height:0!important}
 body{display:block!important}
 .timesheet-print-page{width:100%;margin:0;padding:0;background:#fff}
 .timesheet-print-toolbar{position:sticky;top:0;z-index:9999;display:flex;justify-content:center;gap:10px;padding:12px;background:#070c40;color:#fff;font-family:Arial,Helvetica,sans-serif}
 .timesheet-print-toolbar button{border:0;border-radius:10px;padding:11px 18px;font-weight:900;cursor:pointer}
 .timesheet-print-toolbar .primary{background:#f0f000;color:#070c40}
 .timesheet-print-toolbar .secondary{background:#fff;color:#070c40}
 @media print{
  .timesheet-print-toolbar{display:none!important}
  .timesheet-print-page{margin:0!important;padding:0!important}
 }
</style>
</head>
<body>
<div class="timesheet-print-toolbar no-print"><button class="primary" onclick="window.print()">Imprimir / salvar PDF</button><button class="secondary" onclick="window.close()">Fechar</button></div>
<main class="timesheet-print-page">${report.outerHTML}</main>
</body>
</html>`);
 popup.document.close();

 const triggerPrint=async()=>{
  try{
   const images=Array.from(popup.document.images);
   await Promise.all(images.map((img)=>img.complete?Promise.resolve():new Promise<void>((resolve)=>{img.addEventListener('load',()=>resolve(),{once:true});img.addEventListener('error',()=>resolve(),{once:true});})));
   if('fonts' in popup.document){try{await popup.document.fonts.ready;}catch{} }
  }finally{
   popup.focus();
   window.setTimeout(()=>{try{popup.print();}catch{}},250);
  }
 };

 if(popup.document.readyState==='complete')void triggerPrint();
 else popup.addEventListener('load',()=>{void triggerPrint();},{once:true});
}
