/**
 * Slicker Actions plugin: buttons the app registers with Slicker.addAction().
 *
 * @format
 */

import React, {useState} from 'react';
import {Button, Input, Typography, message} from 'antd';
import {
  createState,
  Layout,
  Panel,
  PluginClient,
  usePlugin,
  useValue,
} from 'flipper-plugin';

type Action = {
  name: string;
  group: string;
  description: string | null;
  // Initial text for the action's text field; null for a plain button.
  input: string | null;
};
type Events = {actions: {actions: Action[]}};
type Methods = {
  run(params: {name: string; value?: string}): Promise<{pending?: boolean}>;
};

export function plugin(client: PluginClient<Events, Methods>) {
  const actions = createState<Action[]>([]);
  const running = createState<string | undefined>(undefined);

  client.onMessage('actions', (event) => actions.set(event.actions));

  const run = async (name: string, value?: string) => {
    running.set(name);
    try {
      const result = await client.send('run', {name, value});
      if (result?.pending) {
        message.info(`${name} is still running on the device`);
      } else {
        message.success(`${name}: done`);
      }
    } catch (e: any) {
      message.error(`${name} failed: ${e?.message ?? e}`);
    } finally {
      running.set(undefined);
    }
  };

  return {actions, running, run};
}

function ActionRow({action}: {action: Action}) {
  const instance = usePlugin(plugin);
  const running = useValue(instance.running);
  const [value, setValue] = useState(action.input ?? '');
  const run = () =>
    instance.run(action.name, action.input !== null ? value : undefined);
  return (
    <Layout.Horizontal gap center>
      {action.input !== null ? (
        <Input
          style={{width: 360}}
          value={value}
          onChange={(e) => setValue(e.target.value)}
          onPressEnter={run}
        />
      ) : null}
      <Button
        type="primary"
        style={{minWidth: 200}}
        loading={running === action.name}
        onClick={run}>
        {action.name}
      </Button>
      {action.description ? (
        <Typography.Text type="secondary">{action.description}</Typography.Text>
      ) : null}
    </Layout.Horizontal>
  );
}

export function Component() {
  const instance = usePlugin(plugin);
  const actions = useValue(instance.actions);

  if (actions.length === 0) {
    return (
      <Layout.Container pad gap>
        <Typography.Text type="secondary">
          No actions yet. Add some in the app with
          Slicker.addAction(&quot;Log out&quot;) &#123; ... &#125;.
        </Typography.Text>
      </Layout.Container>
    );
  }

  const groups = new Map<string, Action[]>();
  actions.forEach((action) => {
    groups.set(action.group, [...(groups.get(action.group) ?? []), action]);
  });

  return (
    <Layout.ScrollContainer>
      <Layout.Container pad gap>
        {[...groups.entries()].map(([group, items]) => (
          <Panel key={group} title={group} collapsible={false}>
            <Layout.Container pad gap>
              {items.map((action) => (
                <ActionRow key={action.name} action={action} />
              ))}
            </Layout.Container>
          </Panel>
        ))}
      </Layout.Container>
    </Layout.ScrollContainer>
  );
}
