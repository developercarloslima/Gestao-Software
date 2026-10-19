export const config = {
  framework: null,
  buildCommand: 'npm run build',
  outputDirectory: 'dist',
  cleanUrls: false,
  functions: {
    'api/**/*.js': {
      maxDuration: 60
    }
  },
  headers: [
    {
      source: '/(.*)',
      headers: [
        { key: 'X-Content-Type-Options', value: 'nosniff' },
        { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
        { key: 'Permissions-Policy', value: 'camera=(self), microphone=(self), geolocation=(self)' }
      ]
    },
    {
      source: '/shared/config.js',
      headers: [{ key: 'Cache-Control', value: 'no-store, max-age=0' }]
    },
    {
      source: '/api/(.*)',
      headers: [{ key: 'Cache-Control', value: 'no-store' }]
    }
  ]
};
