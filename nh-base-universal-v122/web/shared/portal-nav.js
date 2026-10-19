(() => {
  const headers = document.querySelectorAll('.portal-header');
  headers.forEach((header) => {
    const toggle = header.querySelector('.nh-portal-menu-toggle');
    const menu = header.querySelector('.nh-standard-header-actions');
    if (!toggle || !menu) return;

    const closeMenu = () => {
      menu.classList.remove('is-open');
      toggle.classList.remove('is-open');
      toggle.setAttribute('aria-expanded', 'false');
      toggle.setAttribute('aria-label', 'Abrir menu');
    };

    const openMenu = () => {
      menu.classList.add('is-open');
      toggle.classList.add('is-open');
      toggle.setAttribute('aria-expanded', 'true');
      toggle.setAttribute('aria-label', 'Fechar menu');
    };

    toggle.addEventListener('click', (event) => {
      event.stopPropagation();
      menu.classList.contains('is-open') ? closeMenu() : openMenu();
    });

    menu.querySelectorAll('a, button').forEach((control) => {
      control.addEventListener('click', () => {
        if (window.innerWidth < 1180) closeMenu();
      });
    });

    document.addEventListener('click', (event) => {
      if (window.innerWidth >= 1180 || !menu.classList.contains('is-open')) return;
      if (!header.contains(event.target)) closeMenu();
    });

    document.addEventListener('keydown', (event) => {
      if (event.key === 'Escape') closeMenu();
    });

    window.addEventListener('resize', () => {
      if (window.innerWidth >= 1180) closeMenu();
    }, { passive: true });
  });
})();
