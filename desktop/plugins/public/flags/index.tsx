/**
 * Slicker Feature Flags plugin: flags the app reads with Slicker.flag(), with desktop overrides.
 *
 * @format
 */

import React, {useEffect, useState} from 'react';
import {Button, Input, Switch, Typography, message} from 'antd';
import {
  createDataSource,
  createState,
  DataTable,
  DataTableColumn,
  DetailSidebar,
  Layout,
  Panel,
  PluginClient,
  usePlugin,
  useValue,
} from 'flipper-plugin';

type Flag = {
  key: string;
  type: 'boolean' | 'number' | 'decimal' | 'string';
  appValue: unknown;
  override: unknown;
  seen: boolean;
};

type Row = Flag & {
  appValueText: string;
  overrideText: string;
  valueText: string;
};

type Events = {flags: {flags: Flag[]}};
type Methods = {
  setOverride(params: {key: string; value: unknown}): Promise<void>;
  clearOverride(params: {key: string}): Promise<void>;
  clearAll(params: {}): Promise<void>;
};

const text = (value: unknown) =>
  value === null || value === undefined ? '' : String(value);

const columns: DataTableColumn<Row>[] = [
  {key: 'key', title: 'Flag', width: 260},
  {key: 'type', title: 'Type', width: 90},
  {key: 'appValueText', title: 'App value', width: 180},
  {key: 'overrideText', title: 'Override', width: 180},
  {key: 'valueText', title: 'Value in use'},
];

export function plugin(client: PluginClient<Events, Methods>) {
  const rows = createDataSource<Row, 'key'>([], {key: 'key'});
  const selectedKey = createState<string | undefined>(undefined);
  // Bumped on every update so the sidebar re-reads the selected row.
  const version = createState(0);

  client.onMessage('flags', ({flags}) => {
    const keys = new Set(flags.map((f) => f.key));
    [...rows.records()].forEach((row) => {
      if (!keys.has(row.key)) rows.deleteByKey(row.key);
    });
    flags.forEach((flag) => {
      const hasOverride = flag.override !== null && flag.override !== undefined;
      rows.upsert({
        ...flag,
        appValueText: flag.seen ? text(flag.appValue) : '(not read yet)',
        overrideText: hasOverride ? text(flag.override) : '',
        valueText: text(hasOverride ? flag.override : flag.appValue),
      });
    });
    version.update((v) => v + 1);
  });

  const call = async (fn: () => Promise<void>, done: string) => {
    try {
      await fn();
      message.success(done);
    } catch (e: any) {
      message.error(e?.message ?? String(e));
    }
  };

  return {
    rows,
    selectedKey,
    version,
    select: (row: Row | undefined) => selectedKey.set(row?.key),
    setOverride: (key: string, value: unknown) =>
      call(() => client.send('setOverride', {key, value}), `${key} overridden`),
    clearOverride: (key: string) =>
      call(() => client.send('clearOverride', {key}), `${key} override cleared`),
    clearAll: () =>
      call(() => client.send('clearAll', {}), 'All overrides cleared'),
  };
}

function Editor({row}: {row: Row}) {
  const instance = usePlugin(plugin);
  const hasOverride = row.overrideText !== '';
  const current = hasOverride ? row.override : row.appValue;
  const [draft, setDraft] = useState(text(current));
  useEffect(() => setDraft(text(current)), [row.key, text(current)]);

  return (
    <Layout.Container gap pad>
      <Typography.Title level={5} style={{margin: 0}}>
        {row.key}
      </Typography.Title>
      <Typography.Text type="secondary">
        App value: {row.seen ? text(row.appValue) : 'not read yet in this run'}
      </Typography.Text>
      {row.type === 'boolean' ? (
        <Layout.Horizontal gap center>
          <Switch
            checked={current === true}
            onChange={(checked) => instance.setOverride(row.key, checked)}
          />
          <span>{current === true ? 'On' : 'Off'}</span>
        </Layout.Horizontal>
      ) : (
        <Layout.Horizontal gap>
          <Input
            value={draft}
            onChange={(e) => setDraft(e.target.value)}
            onPressEnter={() => instance.setOverride(row.key, draft)}
          />
          <Button
            type="primary"
            onClick={() => instance.setOverride(row.key, draft)}>
            Override
          </Button>
        </Layout.Horizontal>
      )}
      <Button
        disabled={!hasOverride}
        onClick={() => instance.clearOverride(row.key)}>
        Clear override (use app value)
      </Button>
    </Layout.Container>
  );
}

export function Component() {
  const instance = usePlugin(plugin);
  const selectedKey = useValue(instance.selectedKey);
  useValue(instance.version);
  const selected =
    selectedKey !== undefined ? instance.rows.getById(selectedKey) : undefined;
  return (
    <Layout.Container grow>
      <DataTable<Row>
        dataSource={instance.rows}
        columns={columns}
        onSelect={instance.select}
        onRowStyle={(row) =>
          row.overrideText !== '' ? {color: '#f5a623'} : undefined
        }
        extraActions={
          <Button onClick={instance.clearAll}>Clear all overrides</Button>
        }
      />
      <DetailSidebar>
        {selected ? (
          <Panel title="Override" collapsible={false}>
            <Editor row={selected} />
          </Panel>
        ) : null}
      </DetailSidebar>
    </Layout.Container>
  );
}
