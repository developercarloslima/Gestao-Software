(function (globalScope) {
  'use strict';

  const DEFAULT_MAX_INTEGER_DIGITS = 12;

  function digitsOnly(value) {
    return String(value ?? '').replace(/\D/g, '');
  }

  function decimalSeparatorInfo(value) {
    const text = String(value ?? '');
    const commaIndex = text.indexOf(',');
    if (commaIndex >= 0) return { index: commaIndex, separator: ',' };

    const dotMatches = [...text.matchAll(/\./g)];
    if (dotMatches.length === 1) {
      const dotIndex = dotMatches[0].index;
      const trailingDigits = digitsOnly(text.slice(dotIndex + 1)).length;
      if (trailingDigits > 0 && trailingDigits <= 2) {
        return { index: dotIndex, separator: '.' };
      }
    }

    return { index: -1, separator: '' };
  }

  function parse(value) {
    const text = String(value ?? '')
      .trim()
      .replace(/R\$/gi, '')
      .replace(/\s/g, '');

    if (!text || !/\d/.test(text)) return NaN;

    const separator = decimalSeparatorInfo(text);
    let integerDigits;
    let decimalDigits = '';

    if (separator.index >= 0) {
      integerDigits = digitsOnly(text.slice(0, separator.index));
      decimalDigits = digitsOnly(text.slice(separator.index + 1)).slice(0, 2);
    } else {
      integerDigits = digitsOnly(text);
    }

    if (!integerDigits) integerDigits = '0';
    const normalized = `${integerDigits}.${decimalDigits.padEnd(2, '0')}`;
    const number = Number(normalized);
    return Number.isFinite(number) ? number : NaN;
  }

  function format(value) {
    if (value === '' || value == null) return '';
    const number = typeof value === 'number' ? value : parse(value);
    if (!Number.isFinite(number)) return '';

    return new Intl.NumberFormat('pt-BR', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
      useGrouping: true
    }).format(number);
  }

  function groupInteger(integerDigits) {
    const normalized = String(integerDigits || '0').replace(/^0+(?=\d)/, '') || '0';
    return normalized.replace(/\B(?=(\d{3})+(?!\d))/g, '.');
  }

  function formatEditable(value, maxIntegerDigits = DEFAULT_MAX_INTEGER_DIGITS) {
    const text = String(value ?? '').replace(/R\$/gi, '').replace(/\s/g, '');
    if (!/\d/.test(text)) return '';

    const separator = decimalSeparatorInfo(text);
    const integerPart = separator.index >= 0 ? text.slice(0, separator.index) : text;
    const decimalPart = separator.index >= 0 ? text.slice(separator.index + 1) : '';

    let integerDigits = digitsOnly(integerPart).slice(0, maxIntegerDigits);
    if (!integerDigits) integerDigits = '0';

    const decimalDigits = digitsOnly(decimalPart).slice(0, 2).padEnd(2, '0');
    return `${groupInteger(integerDigits)},${decimalDigits}`;
  }

  function caretAfterIntegerDigits(formatted, digitCount) {
    if (digitCount <= 0) return 0;
    const commaIndex = formatted.indexOf(',');
    let seen = 0;
    for (let index = 0; index < commaIndex; index += 1) {
      if (/\d/.test(formatted[index])) seen += 1;
      if (seen >= digitCount) return index + 1;
    }
    return commaIndex;
  }

  function applyMask(input) {
    if (!input || input.dataset.moneyMaskReady === 'true') return input;
    input.dataset.moneyMaskReady = 'true';
    input.autocomplete = input.autocomplete || 'off';
    input.inputMode = 'decimal';

    const maxIntegerDigits = Number(input.dataset.moneyMaxIntegerDigits || DEFAULT_MAX_INTEGER_DIGITS);

    const refresh = () => {
      input.value = formatEditable(input.value, maxIntegerDigits);
      return input.value;
    };

    input.addEventListener('input', event => {
      const raw = input.value;
      const deleting = String(event.inputType || '').startsWith('delete');
      const remainingDigits = digitsOnly(raw);
      if (deleting && remainingDigits && /^0+$/.test(remainingDigits)) {
        input.value = '';
        return;
      }
      const caret = input.selectionStart ?? raw.length;
      const separator = decimalSeparatorInfo(raw);
      const isDecimalEditing = separator.index >= 0 && caret > separator.index;
      const integerDigitsBeforeCaret = digitsOnly(
        raw.slice(0, separator.index >= 0 ? Math.min(caret, separator.index) : caret)
      ).length;
      const decimalDigitsBeforeCaret = separator.index >= 0 && caret > separator.index
        ? digitsOnly(raw.slice(separator.index + 1, caret)).length
        : 0;

      const formatted = formatEditable(raw, maxIntegerDigits);
      input.value = formatted;
      if (!formatted) return;

      const commaIndex = formatted.indexOf(',');
      const nextCaret = isDecimalEditing
        ? commaIndex + 1 + Math.min(decimalDigitsBeforeCaret, 2)
        : caretAfterIntegerDigits(formatted, integerDigitsBeforeCaret);

      try {
        input.setSelectionRange(nextCaret, nextCaret);
      } catch (_) {
        // Alguns navegadores antigos não permitem ajustar o cursor em certos tipos de input.
      }
    });

    input.addEventListener('blur', refresh);
    input.addEventListener('change', refresh);

    if (input.value) refresh();
    return input;
  }

  function attachAll(root = document) {
    if (!root?.querySelectorAll) return [];
    return [...root.querySelectorAll('[data-money]')].map(applyMask);
  }

  const api = Object.freeze({
    parse,
    format,
    formatEditable,
    applyMask,
    attachAll,
    refresh(input) {
      if (!input) return '';
      input.value = formatEditable(input.value, Number(input.dataset.moneyMaxIntegerDigits || DEFAULT_MAX_INTEGER_DIGITS));
      return input.value;
    }
  });

  if (globalScope) globalScope.NHMoney = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;

  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', () => attachAll(document), { once: true });
    } else {
      attachAll(document);
    }
  }
}(typeof window !== 'undefined' ? window : globalThis));
