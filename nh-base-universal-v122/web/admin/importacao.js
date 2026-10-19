'use strict';
const $=id=>document.getElementById(id);
const token=()=>localStorage.getItem('nhPortalToken')||'';
let currentBatch=null;
function status(message,bad=false){$('status').textContent=message;$('status').style.color=bad?'#ad341b':'#344f78';}
function el(tag,content){const node=document.createElement(tag);node.textContent=content??'';return node;}
async function api(path,options={}){
 if(!token()){location.assign('/admin/');throw Error('Entre como administrador para importar clientes.');}
 const response=await fetch('/api/admin/importacao'+path,{...options,headers:{Authorization:'Bearer '+token(),...(options.headers||{})},cache:'no-store'});
 if(response.status===401){location.assign('/admin/');throw Error('Sessão expirada. Faça login novamente.');}
 let data;const type=response.headers.get('Content-Type')||'';
 if(type.includes('application/json'))data=await response.json();else data=await response.text();
 if(!response.ok)throw Error(typeof data==='object'?(data.detail||data.message||data.title||'Não foi possível concluir a operação.'):String(data).slice(0,200));
 return data;
}
function mark(step){for(let i=1;i<=3;i++)$('step-'+i).classList.toggle('active',i===step);}
$('template').addEventListener('click',ev=>{ev.preventDefault();const contents='nome;documento;email;telefone;nascimento;telefone2;cep;endereco;numero;complemento;bairro;cidade;uf;codigoexterno\n';const url=URL.createObjectURL(new Blob(['\uFEFF'+contents],{type:'text/csv;charset=utf-8'}));const a=el('a');a.href=url;a.download='modelo-clientes.csv';a.click();URL.revokeObjectURL(url);});
$('form-upload').addEventListener('submit',async ev=>{ev.preventDefault();const file=$('file').files[0];if(!file){status('Escolha o CSV de clientes.',true);return;}if(file.size>2000000){status('O limite é 2 MB. Divida o arquivo em partes menores.',true);return;}
 $('send').disabled=true;status('Conferindo dados. Nenhum cadastro será alterado nesta etapa...');
 try{const fd=new FormData();fd.set('arquivo',file);fd.set('autorizado',$('authorized').checked?'true':'false');const data=await api('/preparar',{method:'POST',body:fd});currentBatch=data;
 $('preview').hidden=false;$('result').hidden=true;mark(2);$('valid').textContent=String(data.validRows);$('invalid').textContent=String(data.invalidRows);$('duplicates').textContent=String(data.existingRecords);
 $('preview-message').textContent=data.invalidRows?'Corrija as linhas listadas e envie o arquivo novamente. Nenhum cadastro foi alterado.':'Arquivo validado. Registros existentes serão ignorados, sem substituir informações.';
 const tbody=$('preview-data');tbody.replaceChildren();for(const row of data.preview||[]){const tr=document.createElement('tr');for(const key of ['line','name','document','action'])tr.append(el('td',String(row[key]??'')));tbody.append(tr);}
 const issues=$('issues');issues.replaceChildren();for(const issue of data.errors||[]){issues.append(el('p',`Linha ${issue.line}: ${issue.message}`));}
 $('confirm').checked=false;$('apply').disabled=true;$('preview').scrollIntoView({behavior:'smooth',block:'start'});status('Prévia pronta. Confira e confirme para gravar os novos clientes.');await refreshHistory();}
 catch(err){status(err.message,true);}finally{$('send').disabled=false;}
});
$('confirm').addEventListener('change',()=>{$('apply').disabled=!$('confirm').checked || !currentBatch || currentBatch.invalidRows>0;});
$('apply').addEventListener('click',async()=>{if(!currentBatch||!$('confirm').checked)return;$('apply').disabled=true;status('Importando... Não feche a página até receber a confirmação.');try{const r=await api('/'+encodeURIComponent(currentBatch.batchId)+'/confirmar',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({confirmo:true})});
 $('result').hidden=false;$('preview').hidden=true;mark(3);$('result-message').textContent=`${r.inserted} novos clientes cadastrados. ${r.alreadyExisting} já existiam e foram preservados. Nenhum cadastro foi substituído.`;
 $('result').scrollIntoView({behavior:'smooth',block:'start'});status('Importação finalizada com segurança.');await refreshHistory();await refreshCustomers();
 }catch(e){status(e.message,true);$('apply').disabled=false;}});
$('new-upload').addEventListener('click',()=>{currentBatch=null;$('form-upload').reset();$('result').hidden=true;$('preview').hidden=true;mark(1);$('file').focus();});
async function refreshHistory(){const root=$('history');try{const entries=await api('/historico');root.replaceChildren();if(!entries.length){root.append(el('p','Nenhuma importação enviada.'));return;}
 for(const b of entries){const row=document.createElement('div');const info=document.createElement('div');info.append(el('strong',b.fileName),el('small',`${b.status==='IMPORTED'?'Importado':b.status==='READY'?'Aguardando aprovação':'Necessita correção'} · ${b.valid} válidos · ${b.invalid} erros · ${b.inserted} incluídos`));
 const download=document.createElement('button');download.className='button outline';download.type='button';download.textContent='Recuperar CSV original';download.addEventListener('click',()=>downloadOriginal(b.id));row.append(info,download);root.append(row);}}
 catch(e){root.textContent=e.message;}}
async function downloadOriginal(id){try{const response=await fetch('/api/admin/importacao/'+encodeURIComponent(id)+'/arquivo-original',{headers:{Authorization:'Bearer '+token()},cache:'no-store'});if(!response.ok)throw Error('Não foi possível recuperar o arquivo.');const blob=await response.blob();const url=URL.createObjectURL(blob);const a=el('a');a.href=url;a.download='importacao-original.csv';a.click();URL.revokeObjectURL(url);status('Arquivo original recuperado. Armazene-o em local seguro.');}catch(e){status(e.message,true);}}
async function refreshCustomers(){const target=$('customers');try{const rows=await api('/clientes');target.replaceChildren();if(!rows.length){const tr=document.createElement('tr');const td=el('td','Nenhum cliente importado ainda.');td.colSpan=4;tr.append(td);target.append(tr);return;}
for(const person of rows){const tr=document.createElement('tr');for(const key of ['name','maskedDocument','email','phone'])tr.append(el('td',person[key]||'—'));target.append(tr);}}
catch(e){target.replaceChildren(el('tr'));const td=el('td',e.message);td.colSpan=4;target.firstChild.append(td);}}
refreshHistory();
refreshCustomers();
