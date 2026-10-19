(() => {
  'use strict';

  const normalizeLabel = (value, index) => {
    const label = String(value || '').replace(/\s+/g, ' ').trim();
    return label || `Campo ${index + 1}`;
  };

  const primaryHeaderCells = table => Array.from(
    table.querySelectorAll('thead tr:not(.table-scrollbar-row):first-child th')
  );

  const enhanceRow = (row, headers) => {
    if (!(row instanceof HTMLTableRowElement)) return;

    const cells = Array.from(row.cells || []);
    if (!cells.length) return;

    const isFullRow = cells.length === 1 && Number(cells[0].colSpan || 1) > 1;

    cells.forEach((cell, index) => {
      if (!(cell instanceof HTMLTableCellElement) || cell.tagName !== 'TD') return;

      if (isFullRow || cell.hasAttribute('colspan')) {
        cell.dataset.fullRow = 'true';
        cell.removeAttribute('data-label');
        return;
      }

      const label = normalizeLabel(headers[index], index);
      cell.dataset.label = label;
      if (!cell.getAttribute('aria-label')) cell.setAttribute('aria-label', label);
    });
  };

  const updateHeadScrollbar = table => {
    const wrapper = table.closest('.table-wrap');
    const proxy = table.querySelector('.table-head-scrollbar');
    const inner = proxy?.querySelector('.table-head-scrollbar-inner');
    const firstHeader = table.querySelector('thead tr:not(.table-scrollbar-row):first-child');
    if (!wrapper || !proxy || !inner || !firstHeader) return;

    const headerHeight = Math.ceil(firstHeader.getBoundingClientRect().height || 42);
    table.style.setProperty('--table-primary-header-height', `${headerHeight}px`);

    // A largura visual da tabela é medida sem depender da posição do scroll atual.
    const tableWidth = Math.max(Math.ceil(table.getBoundingClientRect().width), wrapper.clientWidth);
    proxy.style.width = `${Math.max(1, wrapper.clientWidth)}px`;
    inner.style.width = `${tableWidth}px`;
    proxy.scrollLeft = wrapper.scrollLeft;

    const needsHorizontalScroll = tableWidth > wrapper.clientWidth + 2;
    table.classList.toggle('has-horizontal-overflow', needsHorizontalScroll);
    proxy.toggleAttribute('hidden', !needsHorizontalScroll);
  };

  const enhanceHeadScrollbar = table => {
    if (!(table instanceof HTMLTableElement) || table.dataset.headScrollbarReady === 'true') return;
    const wrapper = table.parentElement?.classList?.contains('table-wrap') ? table.parentElement : null;
    const thead = table.tHead;
    const firstRow = thead?.rows?.[0];
    if (!wrapper || !thead || !firstRow || !firstRow.cells.length) return;

    const row = document.createElement('tr');
    row.className = 'table-scrollbar-row';
    row.setAttribute('aria-hidden', 'true');

    const cell = document.createElement('th');
    cell.colSpan = firstRow.cells.length;
    cell.className = 'table-scrollbar-cell';

    const proxy = document.createElement('div');
    proxy.className = 'table-head-scrollbar';
    proxy.tabIndex = 0;
    proxy.setAttribute('aria-label', 'Rolagem horizontal da tabela');

    const inner = document.createElement('div');
    inner.className = 'table-head-scrollbar-inner';
    proxy.appendChild(inner);
    cell.appendChild(proxy);
    row.appendChild(cell);
    firstRow.after(row);

    wrapper.classList.add('has-head-scrollbar');
    table.dataset.headScrollbarReady = 'true';

    let syncing = false;
    wrapper.addEventListener('scroll', () => {
      if (syncing) return;
      syncing = true;
      proxy.scrollLeft = wrapper.scrollLeft;
      syncing = false;
    }, { passive: true });

    proxy.addEventListener('scroll', () => {
      if (syncing) return;
      syncing = true;
      wrapper.scrollLeft = proxy.scrollLeft;
      syncing = false;
    }, { passive: true });

    if ('ResizeObserver' in window) {
      const resizeObserver = new ResizeObserver(() => updateHeadScrollbar(table));
      resizeObserver.observe(wrapper);
      resizeObserver.observe(table);
    }

    requestAnimationFrame(() => updateHeadScrollbar(table));
  };

  const enhanceTable = (table) => {
    if (!(table instanceof HTMLTableElement)) return;

    table.classList.add('responsive-table');
    enhanceHeadScrollbar(table);

    const headers = primaryHeaderCells(table).map((header, index) =>
      normalizeLabel(header.textContent, index)
    );

    table.querySelectorAll('tbody tr').forEach((row) => enhanceRow(row, headers));
    table.dataset.responsiveReady = 'true';
    updateHeadScrollbar(table);
  };

  const enhanceTables = (root = document) => {
    if (root instanceof HTMLTableElement) enhanceTable(root);
    root.querySelectorAll?.('table').forEach(enhanceTable);

    const ownerTable = root.closest?.('table');
    if (ownerTable) enhanceTable(ownerTable);
  };

  let scheduled = false;
  const scheduleEnhancement = () => {
    if (scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => {
      scheduled = false;
      enhanceTables(document);
    });
  };

  const start = () => {
    enhanceTables(document);

    const observer = new MutationObserver((records) => {
      const relevant = records.some((record) =>
        Array.from(record.addedNodes || []).some(node =>
          !(node instanceof HTMLElement) || !node.closest?.('.table-scrollbar-row')
        ) || record.type === 'characterData'
      );
      if (relevant) scheduleEnhancement();
    });

    observer.observe(document.body, {
      childList: true,
      subtree: true,
      characterData: true
    });

    window.addEventListener('pageshow', scheduleEnhancement, { passive: true });
    window.addEventListener('resize', scheduleEnhancement, { passive: true });
    window.addEventListener('orientationchange', scheduleEnhancement, { passive: true });
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start, { once: true });
  } else {
    start();
  }
})();
