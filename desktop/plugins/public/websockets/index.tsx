/**
 * Slicker WebSockets plugin: connections and messages from sockets created through
 * Slicker.webSocketFactory().
 *
 * @format
 */

import React from 'react';
import {Button, Typography} from 'antd';
import {DeleteOutlined} from '@ant-design/icons';
import {
  createDataSource,
  createState,
  DataInspector,
  DataTable,
  DataTableColumn,
  DetailSidebar,
  Layout,
  Panel,
  PluginClient,
  usePlugin,
  useValue,
} from 'flipper-plugin';

type Event = {
  id: string;
  connectionId: string;
  url: string;
  timestamp: number;
  direction: 'sent' | 'received' | 'event';
  type: string;
  data: string | null;
  size: number | null;
  headers?: Record<string, string>;
  queued?: boolean;
};

type Row = Event & {
  time: string;
  connection: string;
  dir: string;
  sizeText: string;
  preview: string;
};

type Events = {event: Event};

const columns: DataTableColumn<Row>[] = [
  {key: 'time', title: 'Time', width: 110},
  {key: 'connection', title: 'Connection', width: 240},
  {key: 'dir', title: '', width: 36},
  {key: 'type', title: 'Type', width: 90},
  {key: 'sizeText', title: 'Size', width: 80},
  {key: 'preview', title: 'Data'},
];

const ARROWS = {sent: '↑', received: '↓', event: '•'};

function formatTime(ms: number) {
  const d = new Date(ms);
  const pad = (n: number, l = 2) => n.toString().padStart(l, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(
    d.getSeconds(),
  )}.${pad(d.getMilliseconds(), 3)}`;
}

function formatSize(size: number | null) {
  if (size === null || size === undefined) return '';
  return size < 1024 ? `${size} B` : `${(size / 1024).toFixed(1)} KB`;
}

function shortUrl(url: string) {
  return url.replace(/^wss?:\/\//, '');
}

function parseJson(text: string | null): unknown {
  if (!text) return undefined;
  const trimmed = text.trim();
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) return undefined;
  try {
    return JSON.parse(trimmed);
  } catch {
    return undefined;
  }
}

export function plugin(client: PluginClient<Events, {}>) {
  const rows = createDataSource<Row, 'id'>([], {key: 'id'});
  const selected = createState<Row | undefined>(undefined);
  // Short, stable labels: #1, #2… in the order connections were opened.
  const connectionNumbers = new Map<string, number>();

  client.onMessage('event', (event) => {
    if (!connectionNumbers.has(event.connectionId)) {
      connectionNumbers.set(event.connectionId, connectionNumbers.size + 1);
    }
    const number = connectionNumbers.get(event.connectionId);
    rows.append({
      ...event,
      time: formatTime(event.timestamp),
      connection: `#${number} ${shortUrl(event.url)}`,
      dir: ARROWS[event.direction] ?? '',
      sizeText: formatSize(event.size),
      // OkHttp refuses messages once the socket is closing or gone.
      preview:
        (event.queued === false ? '[not sent: socket closed] ' : '') +
        (event.data ?? '').replace(/\s+/g, ' ').slice(0, 300),
    });
  });

  return {
    rows,
    selected,
    select: (row: Row | undefined) => selected.set(row),
    clear: () => {
      rows.clear();
      selected.set(undefined);
    },
  };
}

const rowStyle = (row: Row) => {
  if (row.type === 'failure' || row.queued === false) return {color: '#ff4d4f'};
  if (row.direction === 'event') return {color: '#8c8c8c'};
  if (row.direction === 'sent') return {color: '#4fa8ff'};
  return undefined;
};

function Details({row}: {row: Row}) {
  const json = parseJson(row.data);
  const direction =
    row.direction === 'sent'
      ? 'Sent by the app'
      : row.direction === 'received'
      ? 'Received from the server'
      : 'Connection event';
  return (
    <Layout.Container gap pad>
      <Panel title="Message" collapsible={false}>
        <DataInspector
          data={{
            connection: row.connection,
            url: row.url,
            direction,
            type: row.type,
            size: row.sizeText || undefined,
            time: new Date(row.timestamp).toISOString(),
            sent:
              row.queued === undefined
                ? undefined
                : row.queued
                ? 'yes'
                : 'no, the socket was already closed',
          }}
          expandRoot
        />
      </Panel>
      {row.headers ? (
        <Panel title="Handshake response headers" collapsible={false}>
          <DataInspector data={row.headers} expandRoot />
        </Panel>
      ) : null}
      {row.data ? (
        <Panel
          title={row.type === 'binary' ? 'Data (base64)' : 'Data'}
          collapsible={false}>
          {json !== undefined ? (
            <DataInspector data={json} expandRoot />
          ) : (
            <Typography.Paragraph
              copyable
              style={{whiteSpace: 'pre-wrap', fontFamily: 'monospace'}}>
              {row.data}
            </Typography.Paragraph>
          )}
        </Panel>
      ) : null}
    </Layout.Container>
  );
}

export function Component() {
  const instance = usePlugin(plugin);
  const selected = useValue(instance.selected);
  return (
    <Layout.Container grow>
      <DataTable<Row>
        dataSource={instance.rows}
        columns={columns}
        enableAutoScroll
        onSelect={instance.select}
        onRowStyle={rowStyle}
        extraActions={
          <Button
            title="Clear"
            icon={<DeleteOutlined />}
            onClick={instance.clear}
          />
        }
      />
      <DetailSidebar width={420}>
        {selected ? <Details row={selected} /> : null}
      </DetailSidebar>
    </Layout.Container>
  );
}
