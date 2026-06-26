import {
  Drawer, Button, Table, Tag, Tooltip, App, Statistic, Row, Col, Empty, Typography,
  Segmented, InputNumber, Space,
} from 'antd';
import { CheckCircleOutlined, SyncOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { reconciliationApi } from '../api/client';
import type { Project, ReconciliationResult, ReconciliationRun } from '../api/types';

const RESULT_COLOR: Record<string, string> = {
  MATCH: 'green', MISMATCH: 'red', ERROR: 'orange', SKIPPED: 'default',
};

export default function ValidationDrawer({ project, onClose }: { project: Project | null; onClose: () => void }) {
  const { message } = App.useApp();
  const qc = useQueryClient();
  const open = project !== null;
  const [mode, setMode] = useState<'COUNT' | 'CHECKSUM'>('COUNT');
  const [sampleSize, setSampleSize] = useState(1000);

  const history = useQuery({
    queryKey: ['reconciliation', project?.id],
    queryFn: () => reconciliationApi.history(project!.id),
    enabled: open,
  });

  const run = useMutation({
    mutationFn: () => reconciliationApi.run(project!.id, mode, sampleSize),
    onSuccess: (r: ReconciliationRun) => {
      message[r.mismatched === 0 && r.status === 'COMPLETED' ? 'success' : 'warning'](
        `Validation ${r.status.toLowerCase()} — ${r.mismatched}/${r.totalTables} mismatched`);
      qc.invalidateQueries({ queryKey: ['reconciliation', project?.id] });
    },
    onError: (e: any) => message.error(e?.response?.data?.message ?? 'Validation failed'),
  });

  const latest = history.data?.[0];
  const isChecksum = latest?.mode === 'CHECKSUM';

  const baseCols = [
    { title: 'Table', render: (_: unknown, r: ReconciliationResult) => `${r.schemaName}.${r.tableName}` },
  ];
  const countCols = [
    { title: 'Source', dataIndex: 'sourceCount', render: (v: number | null) => v ?? '—' },
    { title: 'Target', dataIndex: 'targetCount', render: (v: number | null) => v ?? '—' },
    {
      title: 'Diff', dataIndex: 'difference',
      render: (v: number | null) => (v == null ? '—' : <span style={{ color: v === 0 ? undefined : '#cf1322' }}>{v}</span>),
    },
  ];
  const checksumCols = [
    { title: 'Sampled', dataIndex: 'sampled', render: (v: number | null) => v ?? '—' },
    {
      title: 'Missing in target', dataIndex: 'missing',
      render: (v: number | null) => (v == null ? '—' : <span style={{ color: v === 0 ? undefined : '#cf1322' }}>{v}</span>),
    },
  ];
  const statusCol = [{
    title: 'Status', dataIndex: 'status',
    render: (s: string, r: ReconciliationResult) => {
      const tag = <Tag color={RESULT_COLOR[s] ?? 'default'}>{s}</Tag>;
      return r.error ? <Tooltip title={r.error}>{tag}</Tooltip> : tag;
    },
  }];
  const columns = [...baseCols, ...(isChecksum ? checksumCols : countCols), ...statusCol];

  return (
    <Drawer
      title={project ? `Validate — ${project.name}` : ''}
      width={760}
      open={open}
      onClose={onClose}
      extra={
        <Space>
          <Segmented
            value={mode}
            onChange={(v) => setMode(v as 'COUNT' | 'CHECKSUM')}
            options={[{ label: 'Row counts', value: 'COUNT' }, { label: 'Checksum sample', value: 'CHECKSUM' }]}
          />
          {mode === 'CHECKSUM' && (
            <InputNumber min={10} max={100000} step={500} value={sampleSize}
              onChange={(v) => setSampleSize(v ?? 1000)} addonBefore="sample" style={{ width: 160 }} />
          )}
          <Button type="primary" icon={<SyncOutlined />} loading={run.isPending} onClick={() => run.mutate()}>
            Run validation
          </Button>
        </Space>
      }
    >
      <Typography.Paragraph type="secondary">
        {mode === 'COUNT'
          ? 'Compares row counts per selected table (source vs target). Soft-deleted rows are excluded from the target count when the project uses soft delete.'
          : 'Samples primary keys from the source and checks they exist in the target — catches identity gaps that equal counts can miss. Single-primary-key tables only.'}
      </Typography.Paragraph>

      {latest && (
        <Row gutter={16} style={{ marginBottom: 16 }}>
          <Col span={6}><Statistic title="Mode" value={latest.mode} /></Col>
          <Col span={6}><Statistic title="Tables" value={latest.totalTables} /></Col>
          <Col span={6}>
            <Statistic title="Mismatched" value={latest.mismatched}
              valueStyle={{ color: latest.mismatched > 0 ? '#cf1322' : '#3f8600' }}
              prefix={latest.mismatched === 0 ? <CheckCircleOutlined /> : undefined} />
          </Col>
          <Col span={6}><Statistic title="Run status" value={latest.status} /></Col>
        </Row>
      )}

      {!latest && !history.isLoading
        ? <Empty description="No validation runs yet. Pick a mode and click Run validation." />
        : <Table<ReconciliationResult>
            rowKey={(r) => `${r.schemaName}.${r.tableName}`}
            size="small"
            loading={history.isLoading || run.isPending}
            dataSource={latest?.results}
            columns={columns}
            pagination={false}
          />}
    </Drawer>
  );
}
