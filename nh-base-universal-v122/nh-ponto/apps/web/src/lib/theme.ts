export type BrandTheme={primary?:string;secondary?:string;surface?:string;text?:string;logoUrl?:string|null;source?:string|null;updatedAt?:string|null;hasGuide?:boolean};
function hexRgb(hex:string){const v=hex.replace('#','');if(!/^[0-9a-fA-F]{6}$/.test(v))return [7,12,64];const n=parseInt(v,16);return[(n>>16)&255,(n>>8)&255,n&255];}
function mix(hex:string,other:string,amount:number){const a=hexRgb(hex),b=hexRgb(other);return '#'+a.map((v,i)=>Math.round(v*(1-amount)+b[i]*amount).toString(16).padStart(2,'0')).join('');}
export function applyBrandTheme(brand?:BrandTheme|null){
 const root=document.documentElement;const primary=brand?.primary||'#070C40',secondary=brand?.secondary||'#F0F000',surface=brand?.surface||'#FFFFFF',text=brand?.text||'#FFFFFF';
 root.style.setProperty('--nh-navy',primary);root.style.setProperty('--nh-navy-2',mix(primary,'#FFFFFF',.08));root.style.setProperty('--nh-navy-3',mix(primary,'#FFFFFF',.14));root.style.setProperty('--nh-yellow',secondary);root.style.setProperty('--nh-white',surface);root.style.setProperty('--nh-white-soft',mix(surface,primary,.04));root.style.setProperty('--nh-muted',mix(text,primary,.25));root.style.setProperty('--nh-border',`${secondary}55`);root.style.setProperty('--tenant-text',text);
}
