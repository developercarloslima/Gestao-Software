/* Mostrar/ocultar senha em campos estáticos e adicionados por modais. */
(() => {
  'use strict';

  function mount() {
    if (!document.getElementById('password-visibility-styles')) {
      const style = document.createElement('style');
      style.id = 'password-visibility-styles';
      style.textContent = `
        .password-visibility-field{position:relative;display:block;width:100%;min-width:0}
        .password-visibility-field>input{width:100%;box-sizing:border-box;padding-right:82px!important}
        .password-visibility-toggle{position:absolute!important;top:50%!important;right:7px!important;
          transform:translateY(-50%)!important;z-index:2;width:auto!important;height:auto!important;
          min-height:30px!important;margin:0!important;padding:5px 7px!important;
          background:transparent!important;background-image:none!important;border:0!important;
          box-shadow:none!important;color:#334466!important;font:600 12px/1.2 system-ui,sans-serif!important;
          white-space:nowrap;cursor:pointer;border-radius:6px!important}
        .password-visibility-toggle:focus-visible{outline:2px solid #2368da!important;outline-offset:1px}
      `;
      document.head.appendChild(style);
    }

    function enhance(input) {
      if (!(input instanceof HTMLInputElement) || input.type !== 'password' || input.dataset.passwordVisibility === 'ready') return;
      input.dataset.passwordVisibility = 'ready';
      const wrapper = document.createElement('span');
      wrapper.className = 'password-visibility-field';
      input.parentNode.insertBefore(wrapper, input);
      wrapper.appendChild(input);
      const button = document.createElement('button');
      button.className = 'password-visibility-toggle';
      button.type = 'button';
      button.textContent = 'Mostrar';
      button.setAttribute('aria-label', 'Mostrar senha');
      button.setAttribute('aria-pressed', 'false');
      button.addEventListener('click', event => {
        event.preventDefault();
        event.stopPropagation();
        const visible = input.type === 'password';
        input.type = visible ? 'text' : 'password';
        button.textContent = visible ? 'Ocultar' : 'Mostrar';
        button.setAttribute('aria-label', visible ? 'Ocultar senha' : 'Mostrar senha');
        button.setAttribute('aria-pressed', String(visible));
      });
      wrapper.appendChild(button);
    }

    function enhanceAll(root) {
      if (root instanceof HTMLInputElement) enhance(root);
      if (root.querySelectorAll) root.querySelectorAll('input[type="password"]').forEach(enhance);
    }

    enhanceAll(document);
    new MutationObserver(records => {
      for (const record of records) {
        for (const node of record.addedNodes) if (node.nodeType === 1) enhanceAll(node);
      }
    }).observe(document.documentElement, { childList: true, subtree: true });
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount, { once: true });
  else mount();
})();
