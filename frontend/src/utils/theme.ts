/**
 * Design Tokens
 *
 * Global theme configuration for CSS-in-JS libraries such as Styled Components
 * and Emotion, or for standard React inline styles.
 * Colors are based on the UI design specification and use a cool blue-gray palette.
 */

export const theme = {
  colors: {
    // Base structure colors
    surface: '#FFFFFF',          // Content cards and modal backgrounds
    appBg: '#EDF1F8',            // Global page background
    border: '#D9D9D9',           // Global borders and dividers

    // Typography colors
    text: {
      primary: '#354C7E',        // Default body text and primary headings
      secondary: 'rgba(53, 76, 126, 0.6)', // Secondary text with RGBA opacity control
      inverse: '#FFFFFF',        // Inverse text, such as button labels
    },

    // Brand and action colors
    brand: {
      primary: '#5D7BBC',        // Primary buttons, active states, and progress bars
      light: '#96AAD5',          // Secondary states, hover backgrounds, and subtle highlights
      dark: '#354C7E',           // Pressed states or darker brand extensions
    },

    // AI highlight colors
    ai: {
      accent: '#395FB1',         // Typewriter cursor, key hints, and highlighted borders
      glowStart: '#395FB1',      // Glow gradient start
      glowEnd: '#96AAD5',        // Glow gradient end
    }
  },

  // Extend additional design tokens here, such as shadows and font sizes.
  shadows: {
    sm: '0 1px 2px 0 rgba(53, 76, 126, 0.05)',
    card: '0 4px 6px -1px rgba(53, 76, 126, 0.1), 0 2px 4px -1px rgba(53, 76, 126, 0.06)',
    aiGlow: '0 0 15px rgba(57, 95, 177, 0.5)',
  }
};

export default theme;
