import React from 'react';
import { Card, Button, Input, Switch, Typography, Space, Divider } from 'antd';
import { useThemeContext } from '../contexts/ThemeContext';

const { Title, Text, Paragraph } = Typography;

export const ThemeTestCard: React.FC = () => {
  const { isDarkMode, toggleTheme, isVSCode } = useThemeContext();

  return (
    <Card
      title={`Theme Test Card - ${isDarkMode ? 'Dark' : 'Light'} Mode`}
      style={{ margin: '16px', maxWidth: '600px' }}
    >
      <Space direction="vertical" style={{ width: '100%' }}>
        <div>
          <Text strong>Environment: </Text>
          <Text type={isVSCode ? 'success' : 'warning'}>
            {isVSCode ? 'VSCode Extension' : 'Browser'}
          </Text>
        </div>
        
        <Divider />
        
        <div>
          <Title level={4}>Theme Controls</Title>
          <Space>
            <Text>Dark Mode:</Text>
            <Switch 
              checked={isDarkMode} 
              onChange={toggleTheme}
              disabled={isVSCode}
            />
            {isVSCode && (
              <Text type="secondary" style={{ fontSize: '12px' }}>
                (Theme controlled by VSCode)
              </Text>
            )}
          </Space>
        </div>

        <Divider />

        <div>
          <Title level={4}>UI Components Test</Title>
          <Space direction="vertical" style={{ width: '100%' }}>
            <Input placeholder="Test input field" />
            <Space>
              <Button type="primary">Primary Button</Button>
              <Button>Default Button</Button>
              <Button type="dashed">Dashed Button</Button>
            </Space>
          </Space>
        </div>

        <Divider />

        <div>
          <Title level={4}>Color Samples</Title>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: '8px' }}>
            <div style={{ 
              padding: '8px', 
              backgroundColor: 'var(--vscode-editor-background)',
              border: '1px solid var(--vscode-border)',
              borderRadius: '4px',
              textAlign: 'center'
            }}>
              <Text style={{ color: 'var(--vscode-editor-foreground)' }}>Editor BG</Text>
            </div>
            <div style={{ 
              padding: '8px', 
              backgroundColor: 'var(--vscode-panel-background)',
              border: '1px solid var(--vscode-border)',
              borderRadius: '4px',
              textAlign: 'center'
            }}>
              <Text style={{ color: 'var(--vscode-foreground)' }}>Panel BG</Text>
            </div>
            <div style={{ 
              padding: '8px', 
              backgroundColor: 'var(--vscode-button-background)',
              border: '1px solid var(--vscode-border)',
              borderRadius: '4px',
              textAlign: 'center'
            }}>
              <Text style={{ color: 'var(--vscode-button-foreground)' }}>Button BG</Text>
            </div>
          </div>
        </div>

        <Divider />

        <div>
          <Title level={4}>CSS Variables Status</Title>
          <Paragraph>
            <Text code>--vscode-editor-background:</Text> 
            <span style={{ 
              display: 'inline-block',
              width: '20px',
              height: '20px',
              backgroundColor: 'var(--vscode-editor-background)',
              border: '1px solid var(--vscode-border)',
              marginLeft: '8px',
              verticalAlign: 'middle'
            }} />
          </Paragraph>
          <Paragraph>
            <Text code>--vscode-panel-background:</Text> 
            <span style={{ 
              display: 'inline-block',
              width: '20px',
              height: '20px',
              backgroundColor: 'var(--vscode-panel-background)',
              border: '1px solid var(--vscode-border)',
              marginLeft: '8px',
              verticalAlign: 'middle'
            }} />
          </Paragraph>
          <Paragraph>
            <Text code>--vscode-button-background:</Text> 
            <span style={{ 
              display: 'inline-block',
              width: '20px',
              height: '20px',
              backgroundColor: 'var(--vscode-button-background)',
              border: '1px solid var(--vscode-border)',
              marginLeft: '8px',
              verticalAlign: 'middle'
            }} />
          </Paragraph>
        </div>
      </Space>
    </Card>
  );
};







