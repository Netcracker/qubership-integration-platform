import { Button, Space } from 'antd';
import { applyThemeToDOM, getSavedTheme, saveTheme, ThemeMode, getSystemTheme, resetToSystemTheme, isAutoThemeEnabled } from '../theme/themeInit';
import { AutoThemeIndicator } from './AutoThemeIndicator';

interface ThemeSwitcherProps {
  currentTheme?: ThemeMode;
  onThemeChange?: (theme: ThemeMode) => void;
}

export const ThemeSwitcher = ({ currentTheme, onThemeChange }: ThemeSwitcherProps) => {
  const savedTheme = getSavedTheme();
  const systemTheme = getSystemTheme();
  const activeTheme = currentTheme || savedTheme || systemTheme;
  const isSystemTheme = isAutoThemeEnabled();

  const handleThemeChange = (newTheme: ThemeMode) => {
    saveTheme(newTheme);
    applyThemeToDOM(newTheme);
    onThemeChange?.(newTheme);
  };

  const handleSystemTheme = () => {
    const systemTheme = resetToSystemTheme();
    onThemeChange?.(systemTheme);
  };

  return (
    <Space direction="vertical" size="small" style={{ width: '100%' }}>
      <Space direction="horizontal" size="small" style={{ padding: '8px 16px' }}>
        <Button
          size="small"
          type={isSystemTheme ? 'primary' : 'default'}
          onClick={handleSystemTheme}
          title="Follow system theme (auto-switch)"
        >
          System
        </Button>
        <Button
          size="small"
          type={!isSystemTheme && activeTheme === 'light' ? 'primary' : 'default'}
          onClick={() => handleThemeChange('light')}
          title="Light theme (fixed)"
        >
          Light
        </Button>
        <Button
          size="small"
          type={!isSystemTheme && activeTheme === 'dark' ? 'primary' : 'default'}
          onClick={() => handleThemeChange('dark')}
          title="Dark theme (fixed)"
        >
          Dark
        </Button>
        <Button
          size="small"
          type={!isSystemTheme && activeTheme === 'high-contrast' ? 'primary' : 'default'}
          onClick={() => handleThemeChange('high-contrast')}
          title="High contrast theme (fixed)"
        >
          HC
        </Button>
        <AutoThemeIndicator />
      </Space>
    </Space>
  );
};
