/* v83.137 — Catálogo FIPE guiado. Todos os endpoints são mediados pelo backend NH. */
(function () {
  'use strict';
  const endpoint = '/api/analysis/fipe';
  const alphabetical = (a,b) => a.localeCompare(b,'pt-BR',{sensitivity:'base',numeric:true});
  const norm = text => String(text || '').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLocaleUpperCase('pt-BR').trim();
  const params = values => new URLSearchParams(Object.entries(values).filter(([,value])=>String(value ?? '').trim().length));
  function create({get, api, typeId, brandId, brandListId, modelId, modelListId,
                    onFilterChange, onStatus}) {
    const type = get(typeId), brand = get(brandId), model = get(modelId);
    const brandList = get(brandListId), modelList = get(modelListId);
    if (!type || !brand || !model || !brandList || !modelList) return null;
    let token=0, brandTimer, modelTimer, initialized=false, availableBrands=[];
    let currentCategory='';
    function options(el, values) {
      el.replaceChildren(...values.slice().sort(alphabetical).map(s=>new Option(s,s)));
    }
    function hint(text) { if (onStatus) onStatus(text); }
    function changed() { if (onFilterChange) onFilterChange(); }
    function allowed(types, category) {
      const motorcycle=category==='MOTORCYCLE';
      return types.filter(t=>motorcycle ? norm(t).startsWith('MOT') : !norm(t).startsWith('MOT'));
    }
    function defaultType(types, category) {
      const valid=allowed(types,category);
      const desired=category==='MOTORCYCLE'?'MOT':'CAR';
      return valid.find(t=>norm(t).startsWith(desired)) || valid[0] || '';
    }
    async function loadBrands(expected) {
      if (!type.value) return;
      try {
        const list=await api(`${endpoint}/brands?${params({type:type.value})}`);
        if (expected!==token) return;
        availableBrands=Array.isArray(list)?list:[];
        options(brandList,availableBrands);
        if (!availableBrands.length) hint('Não há marcas cadastradas para esse tipo de veículo.');
      } catch(error) {
        if(expected===token) hint(`Não foi possível carregar as marcas FIPE: ${error.message}`);
      }
    }
    async function loadModels(expected, search='') {
      if (!brand.value.trim()) {modelList.replaceChildren();return;}
      try {
        const list=await api(`${endpoint}/models?${params({type:type.value,brand:brand.value.trim(),search})}`);
        if (expected!==token) return;
        options(modelList,Array.isArray(list)?list:[]);
        if (!list?.length && !search) hint('Nenhum modelo encontrado para essa marca. Confira a grafia ou digite manualmente.');
      } catch(error) {
        if(expected===token) hint(`Não foi possível carregar modelos FIPE: ${error.message}`);
      }
    }
    function stripBrand(modelName) {
      const b=brand.value.trim(), m=String(modelName||'').trim();
      return b && norm(m).startsWith(norm(b)+' ') ? m.slice(b.length).trim() : m;
    }
    function persistedModel() {
      const b=brand.value.trim(),m=model.value.trim();
      return b && m && !norm(m).startsWith(norm(b)+' ') ? `${b} ${m}` : m;
    }
    type.addEventListener('change',()=>{
      ++token;brand.value='';model.value='';availableBrands=[];
      brandList.replaceChildren();modelList.replaceChildren();changed();
      loadBrands(token);
    });
    brand.addEventListener('input',()=>{
      ++token;clearTimeout(brandTimer);model.value='';modelList.replaceChildren();
      changed();
      brandTimer=setTimeout(()=>loadModels(token),240);
    });
    brand.addEventListener('change',()=>{clearTimeout(brandTimer);++token;loadModels(token);changed();});
    model.addEventListener('focus',()=>{if(brand.value && !modelList.options.length)loadModels(token);});
    model.addEventListener('input',()=>{
      clearTimeout(modelTimer);
      // O campo é livre; a lista apenas ajuda na identificação, não impede digitar.
      if(brand.value && model.value.trim().length>1 && !modelList.options.length)
        modelTimer=setTimeout(()=>loadModels(token,model.value.trim()),350);
    });
    async function initialize(item, locked=false) {
      const expected=++token, original=String(item?.vehicleModel || '').trim();
      currentCategory=item?.vehicleType || '';
      brand.value='';model.value=original;brandList.replaceChildren();modelList.replaceChildren();
      brand.disabled=model.disabled=type.disabled=locked;
      type.replaceChildren(new Option('Carregando tipos de veículo...', ''));
      hint('Consultando catálogo de tipos, marcas e modelos FIPE...');
      try {
        const values=await api(`${endpoint}/types`);
        if(expected!==token)return;
        const types=allowed(Array.isArray(values)?values:[],currentCategory).sort(alphabetical);
        type.replaceChildren(...types.map(t=>new Option(t,t)));
        type.value=defaultType(types,currentCategory);
        if(!types.length){type.add(new Option('Nenhum tipo disponível',''));hint('A API FIPE não retornou tipos de veículo.');return;}
        await loadBrands(expected);
        if(expected!==token)return;
        // Mantém compatibilidade com cadastros antigos que guardam "Honda CG" em um só campo.
        const chosen=availableBrands.filter(b=>norm(original)===norm(b)||norm(original).startsWith(norm(b)+' '))
          .sort((a,b)=>b.length-a.length)[0];
        if(chosen){brand.value=chosen;model.value=stripBrand(original);await loadModels(expected);}
        hint(chosen ? `Marca ${chosen} identificada. Confira o modelo e o ano e selecione a versão FIPE.`
                    : 'Selecione/digite a marca, o modelo e o ano para consultar todas as versões.');
        // Consulta atual somente depois de marcar a marca, evitando buscas vagas.
        if(chosen && !locked) changed();
      }catch(error){
        if(expected===token){type.replaceChildren(new Option('Catálogo indisponível',''));hint(`Catálogo FIPE indisponível: ${error.message}`);}
      }
    }
    return {initialize, brand:()=>brand.value.trim(), type:()=>type.value,
            queryModel:()=>stripBrand(model.value), persistedModel};
  }
  window.NHFipePicker={create};
})();
