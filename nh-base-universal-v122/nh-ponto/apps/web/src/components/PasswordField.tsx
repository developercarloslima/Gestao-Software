import { useState, type InputHTMLAttributes } from 'react';

type PasswordFieldProps = Omit<InputHTMLAttributes<HTMLInputElement>, 'type'>;

export default function PasswordField({ style, ...props }: PasswordFieldProps) {
  const [visible, setVisible] = useState(false);

  return <span className="password-visibility-field" style={{ position: 'relative', display: 'block', width: '100%', minWidth: 0 }}>
    <input {...props} type={visible ? 'text' : 'password'} style={{ ...style, boxSizing: 'border-box', width: '100%', paddingRight: 83 }} />
    <button
      type="button"
      aria-label={visible ? 'Ocultar senha' : 'Mostrar senha'}
      aria-pressed={visible}
      onClick={(event) => { event.preventDefault(); event.stopPropagation(); setVisible((current) => !current); }}
      style={{ position: 'absolute', right: 6, top: '50%', transform: 'translateY(-50%)', width: 'auto', minWidth: 0, height: 'auto', minHeight: 0, margin: 0, padding: '4px 7px', border: 0, borderRadius: 6, background: 'transparent', color: '#c7ddff', fontSize: 12, fontWeight: 600, lineHeight: '1.5', cursor: 'pointer', zIndex: 2 }}
    >
      {visible ? 'Ocultar' : 'Mostrar'}
    </button>
  </span>;
}
