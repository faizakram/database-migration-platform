import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App as AntdApp } from 'antd';
import App from './App';
import { AuthProvider } from './auth/AuthContext';
import { ThemeModeProvider } from './theme/ThemeMode';
import '@fontsource-variable/inter';
import 'antd/dist/reset.css';
import './index.css';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
      // Treat data as fresh for 10s so revisiting a page / remounting a drawer doesn't refetch
      // immediately, and keep it cached for 10 min. Live views set their own refetchInterval. (#216)
      staleTime: 10_000,
      gcTime: 10 * 60_000,
    },
  },
});

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ThemeModeProvider>
      <QueryClientProvider client={queryClient}>
        <AntdApp>
          <BrowserRouter>
            <AuthProvider>
              <App />
            </AuthProvider>
          </BrowserRouter>
        </AntdApp>
      </QueryClientProvider>
    </ThemeModeProvider>
  </React.StrictMode>,
);
