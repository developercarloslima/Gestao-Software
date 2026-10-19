import { execFile } from 'node:child_process';
import crypto from 'node:crypto';
import { mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { promisify } from 'node:util';
import jpeg from 'jpeg-js';
import { prisma } from '../lib/prisma.js';

const execFileAsync=promisify(execFile);
const baseDir=process.env.BRANDING_STORAGE_DIR?.trim()||'/tmp/nh-ponto-branding';

function normalizeHex(value:string){
  const v=value.trim().toUpperCase();
  return /^#[0-9A-F]{6}$/.test(v)?v:null;
}
function rgbHex(r:number,g:number,b:number){return '#'+[r,g,b].map(x=>Math.max(0,Math.min(255,x)).toString(16).padStart(2,'0')).join('').toUpperCase();}
function luminance(hex:string){const n=parseInt(hex.slice(1),16);const r=(n>>16)&255,g=(n>>8)&255,b=n&255;return (0.2126*r+0.7152*g+0.0722*b)/255;}
function saturation(hex:string){const n=parseInt(hex.slice(1),16);const vals=[(n>>16)&255,(n>>8)&255,n&255];const max=Math.max(...vals),min=Math.min(...vals);return max===0?0:(max-min)/max;}
function unique<T>(items:T[]){return [...new Set(items)];}
function selectPalette(colors:string[]){
  const valid=unique(colors.map(normalizeHex).filter(Boolean) as string[]);
  const white=valid.find(x=>luminance(x)>.9&&saturation(x)<.15)??'#FFFFFF';
  const dark=valid.filter(x=>luminance(x)<.42).sort((a,b)=>luminance(a)-luminance(b))[0]??'#070C40';
  const accent=valid.filter(x=>x!==dark&&x!==white).sort((a,b)=>(saturation(b)*.7+luminance(b)*.3)-(saturation(a)*.7+luminance(a)*.3))[0]??'#F0F000';
  return {primary:dark,secondary:accent,surface:white,text:white,detected:valid};
}
function colorsFromText(text:string){
  const out:string[]=[];
  for(const m of text.matchAll(/#([0-9a-fA-F]{6})\b/g))out.push(`#${m[1].toUpperCase()}`);
  for(const m of text.matchAll(/RGB\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)/gi))out.push(rgbHex(Number(m[1]),Number(m[2]),Number(m[3])));
  return unique(out);
}
function cropLogoJpeg(bytes:Buffer){
  const img=jpeg.decode(bytes,{useTArray:true});
  const {width,height,data}=img;
  const samples=[[4,4],[width-5,4],[4,height-5],[width-5,height-5]].map(([x,y])=>{const i=(y*width+x)*4;return [data[i],data[i+1],data[i+2]]});
  const bg=samples.reduce((a,v)=>[a[0]+v[0],a[1]+v[1],a[2]+v[2]],[0,0,0]).map(v=>v/samples.length);
  let minX=width,minY=height,maxX=0,maxY=0,found=0;
  for(let y=0;y<height;y+=2){for(let x=0;x<width;x+=2){const i=(y*width+x)*4;const d=Math.hypot(data[i]-bg[0],data[i+1]-bg[1],data[i+2]-bg[2]);if(d>45){minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);found++;}}}
  if(found<100)return bytes;
  const pad=Math.round(Math.min(width,height)*.04);minX=Math.max(0,minX-pad);minY=Math.max(0,minY-pad);maxX=Math.min(width-1,maxX+pad);maxY=Math.min(height-1,maxY+pad);
  const w=Math.max(1,maxX-minX+1),h=Math.max(1,maxY-minY+1);const out=Buffer.alloc(w*h*4);
  for(let y=0;y<h;y++){const src=((minY+y)*width+minX)*4;const dst=y*w*4;Buffer.from(data.buffer,data.byteOffset+src,w*4).copy(out,dst);}
  return Buffer.from(jpeg.encode({width:w,height:h,data:new Uint8Array(out)},88).data);
}
function storagePath(tenantId:string,file:string){return path.join(baseDir,tenantId,file);}

export async function importBrandGuide(input:{tenantId:string;fileName:string;dataUrl:string}){
  const match=input.dataUrl.match(/^data:application\/pdf;base64,(.+)$/s);if(!match)throw new Error('Envie um PDF válido do manual da marca.');
  const pdf=Buffer.from(match[1],'base64');if(pdf.length<100||!pdf.subarray(0,4).equals(Buffer.from('%PDF')))throw new Error('O arquivo enviado não parece ser um PDF válido.');
  if(pdf.length>12*1024*1024)throw new Error('O manual da marca deve ter no máximo 12 MB.');
  const dir=storagePath(input.tenantId,'');await mkdir(dir,{recursive:true});
  const hash=crypto.createHash('sha256').update(pdf).digest('hex').slice(0,16);const guideKey=`manual-${hash}.pdf`;await writeFile(storagePath(input.tenantId,guideKey),pdf,{mode:0o600});
  const tmp=await mkdtemp(path.join(os.tmpdir(),'nh-brand-'));
  let logoKey:string|undefined;let pageCount=1;let text='';
  try{
    const pdfPath=path.join(tmp,'manual.pdf');await writeFile(pdfPath,pdf);
    try{const {stdout}=await execFileAsync('pdftotext',[pdfPath,'-'],{maxBuffer:4*1024*1024});text=stdout;}catch{}
    try{const {stdout}=await execFileAsync('pdfinfo',[pdfPath]);const m=stdout.match(/^Pages:\s+(\d+)/mi);pageCount=m?Math.max(1,Number(m[1])):1;}catch{}
    try{
      const prefix=path.join(tmp,'logo-candidate');await execFileAsync('pdftoppm',['-f',String(pageCount),'-singlefile','-jpeg','-r','120',pdfPath,prefix],{maxBuffer:1024*1024});
      const rendered=await readFile(prefix+'.jpg');const cropped=cropLogoJpeg(rendered);logoKey=`logo-auto-${hash}.jpg`;await writeFile(storagePath(input.tenantId,logoKey),cropped,{mode:0o600});
    }catch{}
  }finally{await rm(tmp,{recursive:true,force:true}).catch(()=>undefined);}
  const palette=selectPalette(colorsFromText(text));
  const tenant=await prisma.tenant.update({where:{id:input.tenantId},data:{brandPrimary:palette.primary,brandSecondary:palette.secondary,brandSurface:palette.surface,brandText:palette.text,brandGuideStorageKey:guideKey,...(logoKey?{brandLogoStorageKey:logoKey}:{}),brandSource:'PDF_AUTO',brandUpdatedAt:new Date()}});
  return {tenant,palette:{...palette,pageCount},logoExtracted:Boolean(logoKey)};
}

export async function saveBrandLogo(input:{tenantId:string;dataUrl:string}){
  const m=input.dataUrl.match(/^data:image\/(png|jpeg|jpg|webp);base64,(.+)$/s);if(!m)throw new Error('Envie uma imagem PNG, JPG ou WEBP válida.');
  const bytes=Buffer.from(m[2],'base64');if(bytes.length>5*1024*1024)throw new Error('A logo deve ter no máximo 5 MB.');
  const ext=m[1]==='jpeg'?'jpg':m[1];const hash=crypto.createHash('sha256').update(bytes).digest('hex').slice(0,16);const key=`logo-${hash}.${ext}`;
  await mkdir(storagePath(input.tenantId,''),{recursive:true});await writeFile(storagePath(input.tenantId,key),bytes,{mode:0o600});
  return prisma.tenant.update({where:{id:input.tenantId},data:{brandLogoStorageKey:key,brandSource:'MANUAL_LOGO',brandUpdatedAt:new Date()}});
}

export async function readTenantBrandFile(tenantId:string,key:string){
  const safe=path.basename(key);return readFile(storagePath(tenantId,safe));
}

export function tenantBrand(tenant:any){
  return {primary:tenant.brandPrimary||'#070C40',secondary:tenant.brandSecondary||'#F0F000',surface:tenant.brandSurface||'#FFFFFF',text:tenant.brandText||'#FFFFFF',source:tenant.brandSource??null,updatedAt:tenant.brandUpdatedAt??null,logoUrl:tenant.brandLogoStorageKey?`/ponto-api/public/branding/${tenant.id}/logo`:null,hasGuide:Boolean(tenant.brandGuideStorageKey)};
}
