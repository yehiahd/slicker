/**
 * Slicker Analytics plugin: shows events the app sends with Slicker.logEvent().
 *
 * @format
 */

import React from 'react';
import {Button} from 'antd';
import {DeleteOutlined} from '@ant-design/icons';
import {
  createDataSource,
  DataInspector,
  DataTable,
  DataTableColumn,
  DetailSidebar,
  Layout,
  Panel,
  PluginClient,
  usePlugin,
  createState,
  useValue,
} from 'flipper-plugin';

type EventParams = {
  id: string;
  timestamp: number;
  name: string;
  source?: string;
  properties: Record<string, unknown>;
};

type Row = EventParams & {
  time: string;
  propertiesText: string;
};

type Events = {newEvent: EventParams};

const columns: DataTableColumn<Row>[] = [
  {key: 'time', title: 'Time', width: 110},
  {key: 'name', title: 'Event', width: 260},
  {key: 'source', title: 'Source', width: 110},
  {key: 'propertiesText', title: 'Properties'},
];

function formatTime(ms: number) {
  const d = new Date(ms);
  const pad = (n: number, l = 2) => n.toString().padStart(l, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(
    d.getSeconds(),
  )}.${pad(d.getMilliseconds(), 3)}`;
}

export function plugin(client: PluginClient<Events, {}>) {
  const rows = createDataSource<Row, 'id'>([], {key: 'id'});
  const selected = createState<Row | undefined>(undefined);

  client.onMessage('newEvent', (event) => {
    const properties = event.properties ?? {};
    rows.upsert({
      ...event,
      source: event.source ?? '',
      properties,
      time: formatTime(event.timestamp),
      propertiesText: Object.entries(properties)
        .map(([k, v]) => `${k}=${typeof v === 'object' ? JSON.stringify(v) : v}`)
        .join(', '),
    });
  });

  return {
    rows,
    selected,
    clear: () => {
      rows.clear();
      selected.set(undefined);
    },
    select: (row: Row | undefined) => selected.set(row),
  };
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
        extraActions={
          <Button
            title="Clear events"
            icon={<DeleteOutlined />}
            onClick={instance.clear}
          />
        }
      />
      <DetailSidebar>
        {selected ? (
          <Layout.Container gap pad>
            <Panel title="Event" collapsible={false}>
              <DataInspector
                data={{
                  name: selected.name,
                  source: selected.source || undefined,
                  time: new Date(selected.timestamp).toISOString(),
                }}
                expandRoot
              />
            </Panel>
            <Panel title="Properties" collapsible={false}>
              <DataInspector data={selected.properties} expandRoot />
            </Panel>
          </Layout.Container>
        ) : null}
      </DetailSidebar>
    </Layout.Container>
  );
}
