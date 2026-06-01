/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{html,ts,scss}'],
  darkMode: 'class',
  // Preflight off — same reason as in eternal-web: Tailwind's global
  // border reset collides with Material-MDC's notched outline. styles.scss
  // does the few resets we actually need.
  corePlugins: { preflight: false },
  theme: {
    extend: {
      colors: {
        // Plugin accent colours — match the &dEternal pink prefix so the
        // landing page reads as the same product as the in-game messages.
        eternal: {
          50:  '#fff0fb',
          100: '#ffd8f5',
          200: '#ffb1ec',
          300: '#ff7add',
          400: '#ff4dd2',
          500: '#ff21c5',
          600: '#e80aab',
          700: '#b8068a',
          800: '#8f0a6d',
          900: '#5f0648'
        },
        ink: {
          900: '#0a0a0e',
          800: '#13131a',
          700: '#1c1c25',
          600: '#262633',
          500: '#3a3a4b',
          400: '#6e6e82',
          300: '#9b9bb0',
          200: '#c7c7d4',
          100: '#e7e7ee'
        }
      },
      fontFamily: {
        display: ['"Inter"', 'system-ui', 'sans-serif']
      }
    }
  },
  plugins: []
};
