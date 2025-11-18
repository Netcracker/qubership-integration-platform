import React, { useState, useEffect } from 'react';
import { Card, Collapse, Tag, Typography, Space, Button, Divider, message } from 'antd';
import { BugOutlined, EyeInvisibleOutlined, CopyOutlined, ReloadOutlined } from '@ant-design/icons';
import VSCodeApiSingleton from '../utils/vscodeApi';

const { Panel } = Collapse;
const { Text, Paragraph } = Typography;

interface ThemeData {
  kind: number;
  isDark: boolean;
  isLight: boolean;
  isHighContrast: boolean;
  themeName: string;
  colors: Record<string, string>;
  fonts: {
    fontFamily: string;
    fontSize: number;
    fontWeight: string;
    lineHeight: number;
  };
  ui: {
    tabSize: number;
    wordWrap: string;
    minimap: {
      enabled: boolean;
      maxColumn: number;
    };
    scrollbar: {
      vertical: string;
      horizontal: string;
      verticalScrollbarSize: number;
      horizontalScrollbarSize: number;
    };
  };
  accessibility: {
    highContrast: boolean;
    reducedMotion: string;
  };
  debug: {
    timestamp: string;
    extensionVersion: string;
    vscodeVersion: string;
    themeKindValues: Record<string, number>;
  };
}

const DebugInfo: React.FC = () => {
  const [themeData, setThemeData] = useState<ThemeData | null>(null);
  const [isVisible, setIsVisible] = useState(false);
  const [lastUpdate, setLastUpdate] = useState<string>('');
  const [domSnapshot, setDomSnapshot] = useState<{ dataTheme: string; bodyClassList: string; cssVars: Record<string,string> }>({ dataTheme: '', bodyClassList: '', cssVars: {} });
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [isReinitializing, setIsReinitializing] = useState(false);

  // Use VS Code API singleton
  const vscodeApiSingleton = VSCodeApiSingleton.getInstance();

  console.log('DebugInfo component rendered, isVisible:', isVisible);

  useEffect(() => {
    // Initialize VS Code API through singleton
    const api = vscodeApiSingleton.getApi();
    if (api) {
      console.log('DebugInfo: VS Code API available through singleton');
    } else {
      console.warn('DebugInfo: VS Code API not available through singleton');
    }
  }, []);

  useEffect(() => {
    const handleMessage = (event: MessageEvent) => {
      const message = event.data;
      if (message.command === 'themeUpdate' && message.theme) {
        setThemeData(message.theme);
        setLastUpdate(new Date().toLocaleTimeString());
        console.log('DebugInfo: Received theme data:', message.theme);

        try {
          const root = document.documentElement;
          const computed = getComputedStyle(root);
          const keysOfInterest = [
            'editor-background',
            'editor-foreground',
            'panel-background',
            'sideBar-background',
            'button-background',
            'button-foreground',
            'input-background',
            'input-foreground',
            'input-border',
            'foreground',
            'descriptionForeground',
            'border',
            'textLink-foreground',
            'warningForeground',
            'errorForeground',
            'terminal-ansiGreen'
          ];

          const cssVars: Record<string, string> = {};
          keysOfInterest.forEach(k => {
            const varName = `--vscode-${k}`;
            cssVars[varName] = computed.getPropertyValue(varName).trim();
          });

          setDomSnapshot({
            dataTheme: root.getAttribute('data-theme') || '',
            bodyClassList: Array.from(document.body.classList).join(' '),
            cssVars
          });
        } catch {}
      }
    };

    window.addEventListener('message', handleMessage);
    
    // Request initial theme if API is available
    if (vscodeApiSingleton.isAvailable()) {
      setTimeout(() => {
        vscodeApiSingleton.sendMessage({ command: 'requestTheme' });
        console.log('DebugInfo: Requested initial theme');
      }, 100);
    }
    
    return () => window.removeEventListener('message', handleMessage);
  }, []);

  const getThemeKindText = (kind: number) => {
    if (kind === 1) return 'Light';
    if (kind === 2) return 'Dark';
    if (kind === 3) return 'High Contrast';
    if (kind === 4) return 'High Contrast Light';
    return 'Unknown';
  };

  const getThemeStatusColor = () => {
    if (!themeData) return 'default';
    if (themeData.isDark) return 'blue';
    if (themeData.isLight) return 'orange';
    if (themeData.isHighContrast) return 'purple';
    return 'green';
  };

  if (!isVisible) {
    return (
      <button
        onClick={() => {
          // Request fresh theme when opening the debug panel
          if (vscodeApiSingleton.isAvailable()) {
            vscodeApiSingleton.sendMessage({ command: 'requestTheme' });
            console.log('DebugInfo: Requested theme on panel open via singleton');
          }
          setIsVisible(true);
        }}
        style={{
          backgroundColor: '#1890ff',
          color: 'white',
          padding: '4px 12px',
          border: 'none',
          borderRadius: '4px',
          cursor: 'pointer',
          fontSize: '12px',
          fontWeight: '500',
          display: 'flex',
          alignItems: 'center',
          gap: '4px'
        }}
      >
        🐛 Debug
      </button>
    );
  }

  const copyDebugInfo = async () => {
    try {
      const root = document.documentElement;
      const computed = getComputedStyle(root);
      const keysOfInterest = [
        'editor-background',
        'editor-foreground',
        'panel-background',
        'sideBar-background',
        'button-background',
        'button-foreground',
        'input-background',
        'input-foreground',
        'input-border',
        'foreground',
        'descriptionForeground',
        'border',
        'textLink-foreground',
        'warningForeground',
        'errorForeground',
        'terminal-ansiGreen'
      ];

      const cssVars: Record<string, string> = {};
      keysOfInterest.forEach(k => {
        const varName = `--vscode-${k}`;
        cssVars[varName] = computed.getPropertyValue(varName).trim();
      });

      const lines: string[] = [];
      lines.push('Debug Snapshot');
      lines.push(`data-theme: ${root.getAttribute('data-theme') || ''}`);
      lines.push(`body.classList: ${Array.from(document.body.classList).join(' ')}`);
      if (themeData) {
        lines.push(`theme.kind: ${themeData.kind}`);
        lines.push(`theme.name: ${themeData.themeName}`);
        lines.push(`theme.isDark: ${themeData.isDark}`);
        lines.push(`theme.isHighContrast: ${themeData.isHighContrast}`);
      }
      lines.push('Computed CSS Variables:');
      Object.entries(cssVars).forEach(([k, v]) => lines.push(`${k}: ${v || 'NOT SET'}`));

      const text = lines.join('\n');
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(text);
      } else {
        const ta = document.createElement('textarea');
        ta.value = text;
        ta.style.position = 'fixed';
        ta.style.opacity = '0';
        document.body.appendChild(ta);
        ta.focus();
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      }
      message.success('Debug info copied');
    } catch (e) {
      message.error('Failed to copy debug info');
    }
  };

  const forceRefreshTheme = () => {
    setIsRefreshing(true);
    try {
      if (vscodeApiSingleton.isAvailable()) {
        vscodeApiSingleton.sendMessage({ command: 'requestTheme' });
        message.info('Requesting theme update...');
        console.log('DebugInfo: Sent requestTheme message via singleton');
      } else {
        console.error('DebugInfo: VS Code API not available through singleton');
        message.warning('VS Code API not available. Check console for details.');
      }
    } catch (e) {
      console.error('DebugInfo: Error requesting theme:', e);
      message.error('Failed to request theme: ' + e.message);
    } finally {
      setTimeout(() => setIsRefreshing(false), 1000);
    }
  };

  const reinitializeApi = () => {
    setIsReinitializing(true);
    try {
      console.log('DebugInfo: Reinitializing VS Code API through singleton...');
      
      // Try force search for existing API
      const found = vscodeApiSingleton.forceFindApi();
      
      if (found) {
        message.success('VS Code API reinitialized successfully');
        console.log('DebugInfo: VS Code API reinitialized successfully via singleton');
      } else {
        message.error('Failed to reinitialize VS Code API - no valid API found');
        console.error('DebugInfo: Failed to reinitialize VS Code API via singleton');
      }
    } catch (e) {
      console.error('DebugInfo: Error reinitializing VS Code API:', e);
      message.error('Error reinitializing VS Code API: ' + e.message);
    } finally {
      setTimeout(() => setIsReinitializing(false), 1000);
    }
  };

  return (
    <div style={{ 
      position: 'fixed', 
      top: '10px', 
      right: '10px', 
      zIndex: 9999,
      width: '400px',
      maxHeight: '80vh',
      overflow: 'auto'
    }}>
      <Card
        title={
          <Space>
            <BugOutlined />
            <span>VS Code Theme Debug</span>
                <Button
                  type="text"
                  icon={<ReloadOutlined />}
                  onClick={forceRefreshTheme}
                  loading={isRefreshing}
                  size="small"
                  title="Force refresh theme data"
                />
                <Button
                  type="text"
                  icon={<CopyOutlined />}
                  onClick={copyDebugInfo}
                  size="small"
                />
                <Button
                  type="text"
                  icon={<ReloadOutlined />}
                  onClick={reinitializeApi}
                  loading={isReinitializing}
                  size="small"
                  title="Reinitialize VS Code API"
                />
            <Button
              type="text"
              icon={<EyeInvisibleOutlined />}
              onClick={() => setIsVisible(false)}
              size="small"
            />
          </Space>
        }
        size="small"
        className="debug-info"
        style={{ 
          backgroundColor: 'var(--vscode-panel-background)',
          border: '1px solid var(--vscode-border)',
          color: 'var(--vscode-foreground)'
        }}
      >
        <Space direction="vertical" style={{ width: '100%' }}>
          <div>
            <Text strong>Status: </Text>
            <Tag color={getThemeStatusColor()}>
              {themeData ? getThemeKindText(themeData.kind) : 'No Data'}
            </Tag>
            {themeData && (
              <Tag color={themeData.isDark ? 'blue' : 'orange'}>
                {themeData.isDark ? 'Dark Mode' : 'Light Mode'}
              </Tag>
            )}
          </div>

          {lastUpdate && (
            <Text type="secondary">Last update: {lastUpdate}</Text>
          )}

          {themeData ? (
            <Collapse size="small">
              <Panel header="Theme Information" key="1">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <div>
                    <Text strong>Theme Name:</Text> {themeData.themeName}
                  </div>
                  <div>
                    <Text strong>Kind:</Text> {themeData.kind} ({getThemeKindText(themeData.kind)})
                  </div>
                  <div>
                    <Text strong>High Contrast:</Text> {themeData.accessibility.highContrast ? 'Yes' : 'No'}
                  </div>
                </Space>
              </Panel>

              <Panel header="Font Settings" key="2">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <div><Text strong>Family:</Text> {themeData.fonts.fontFamily}</div>
                  <div><Text strong>Size:</Text> {themeData.fonts.fontSize}px</div>
                  <div><Text strong>Weight:</Text> {themeData.fonts.fontWeight}</div>
                  <div><Text strong>Line Height:</Text> {themeData.fonts.lineHeight}</div>
                </Space>
              </Panel>

              <Panel header="Colors" key="3">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <Text strong>Available Colors: {Object.keys(themeData.colors).length}</Text>
                  {Object.keys(themeData.colors).length > 0 ? (
                    <div style={{ maxHeight: '150px', overflow: 'auto' }}>
                      {Object.entries(themeData.colors).map(([key, value]) => (
                        <div key={key} style={{ fontSize: '12px' }}>
                          <Text code>{key}:</Text> {value}
                        </div>
                      ))}
                    </div>
                  ) : (
                    <Text type="secondary">No colors available</Text>
                  )}
                </Space>
              </Panel>

              <Panel header="Debug Info" key="4">
                <Space direction="vertical" style={{ width: '100%' }}>
                  <div><Text strong>Timestamp:</Text> {themeData.debug.timestamp}</div>
                  <div><Text strong>Extension Version:</Text> {themeData.debug.extensionVersion}</div>
                  <div><Text strong>VS Code Version:</Text> {themeData.debug.vscodeVersion}</div>
                  <Divider />
                  <div><Text strong>data-theme:</Text> {domSnapshot.dataTheme || 'N/A'}</div>
                  <div><Text strong>body.classList:</Text> {domSnapshot.bodyClassList || 'N/A'}</div>
                  <Divider />
                  <Text strong>Computed CSS Variables:</Text>
                  <div style={{ fontSize: '12px' }}>
                    {Object.entries(domSnapshot.cssVars).map(([k,v]) => (
                      <div key={k}><Text code>{k}</Text>: {v || 'NOT SET'}</div>
                    ))}
                  </div>
                  <Divider />
                  <Text strong>Theme Kind Values:</Text>
                  <div style={{ fontSize: '12px' }}>
                    {Object.entries(themeData.debug.themeKindValues).map(([key, value]) => (
                      <div key={key}>
                        <Text code>{key}:</Text> {value}
                      </div>
                    ))}
                  </div>
                </Space>
              </Panel>
            </Collapse>
          ) : (
            <div style={{ textAlign: 'center', padding: '20px' }}>
              <Text type="secondary">Waiting for theme data from VS Code extension...</Text>
            </div>
          )}
        </Space>
      </Card>
    </div>
  );
};

export default DebugInfo;
