import { Layout, Menu, Typography } from 'antd';
import {
  DashboardOutlined, DatabaseOutlined, ProjectOutlined,
} from '@ant-design/icons';
import { useLocation, useNavigate } from 'react-router-dom';
import type { ReactNode } from 'react';

const { Header, Sider, Content } = Layout;

const items = [
  { key: '/', icon: <DashboardOutlined />, label: 'Dashboard' },
  { key: '/projects', icon: <ProjectOutlined />, label: 'Projects' },
  { key: '/connections', icon: <DatabaseOutlined />, label: 'Connections' },
];

export default function AppLayout({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const location = useLocation();
  const selected = items.find((i) => i.key !== '/' && location.pathname.startsWith(i.key))?.key
    ?? '/';

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider breakpoint="lg" collapsedWidth="0">
        <div style={{ height: 56, margin: 16, color: '#fff', display: 'flex', alignItems: 'center' }}>
          <Typography.Text strong style={{ color: '#fff', fontSize: 16 }}>
            CDC Migration
          </Typography.Text>
        </div>
        <Menu
          theme="dark"
          mode="inline"
          selectedKeys={[selected]}
          items={items}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header style={{ background: '#fff', paddingInline: 24 }}>
          <Typography.Title level={4} style={{ margin: '16px 0' }}>
            Heterogeneous Database Migration (CDC)
          </Typography.Title>
        </Header>
        <Content style={{ margin: 24 }}>{children}</Content>
      </Layout>
    </Layout>
  );
}
