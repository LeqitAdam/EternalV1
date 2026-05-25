/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ['./src/**/*.{html,ts,scss}'],
  darkMode: 'class',
  // Preflight ist aus: Tailwind resettet alle Borders global mit
  // border-style:solid + border-width:0 — das kollidiert mit Material-MDC,
  // dessen notched-outline auf den Browser-Defaults aufbaut. Wir bringen die
  // wenigen Resets, die wir wirklich brauchen, in styles.scss manuell rein.
  corePlugins: { preflight: false },
  theme: {
    extend: {
      colors: {
        // Plugin-Akzentfarben — passend zum &dEternal Pink-Prefix
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
