#!/usr/bin/env node
// Local SDK USB transport. It never signs into, or uploads data to, a cloud service.
const USB = require('@fitbit/sdk-cli/lib/models/USBDebugHost');
const { HostConnection } = require('@fitbit/sdk-cli/lib/models/HostConnections');
const AppContext = require('@fitbit/sdk-cli/lib/models/AppContext').default;
const sideload = require('@fitbit/sdk-cli/lib/models/sideload');
const captureScreenshot = require('@fitbit/sdk-cli/lib/models/captureScreenshot').default;
const path = require('node:path');
const { setTimeout: delay } = require('node:timers/promises');

const action = process.argv[2] || 'info';
if (!['info', 'install', 'launch', 'screenshot'].includes(action)) {
  console.error('Usage: npm run usb -- info | install | launch | screenshot <new-output.png>');
  process.exit(2);
}
(async () => {
  let stream;
  // Installation may wait for watch-side confirmation. Keep the operation bounded.
  const watchdog = setTimeout(() => {
    console.error('USB operation exceeded 90 seconds; closing without retry.');
    if (stream) stream.destroy();
    // SDK finalize RPC timers otherwise keep a disconnected process alive for minutes.
    setTimeout(() => process.exit(1), 200);
  }, 90000);
  try {
    const devices = await USB.list();
    if (devices.length !== 1) throw Error(`Expected one Fitbit USB debug host; found ${devices.length}.`);
    stream = await devices[0].connect();
    stream.on('error', error => console.error('USB stream:', error.message));
    const connection = await HostConnection.connect(stream);
    const host = connection.host;
    // No sensor values are logged or saved by this tool.
    host.on('consoleMessage', message => {
      if (message.kind === 'error') console.error('WATCH ERROR',
        message.emittedBy?.buildID || 'unknown-build', ...(message.message || []));
    });
    console.log(JSON.stringify({ device: host.info.device, hardware: host.info.hardware,
      capabilities: host.info.capabilities }, null, 2));
    if (action === 'install' || action === 'launch') {
      const app = await new AppContext().loadAppPackage(path.resolve(__dirname, '../build/app.fba'));
      if (action === 'install') {
        const installed = await sideload.app(host, app);
        console.log('Watch component:', installed ? installed.installType : 'already installed');
      }
      console.log('Launching STRIDE HR Probe. Phone companion is installed separately.');
      await host.launchAppComponent({ uuid: app.uuid, component: 'app' });
      console.log('Watch launch acknowledged.');
    } else if (action === 'screenshot') {
      if (!process.argv[3]) throw Error('Provide a new screenshot file path. Existing files are not overwritten.');
      await captureScreenshot(host, path.resolve(process.argv[3]));
      console.log('Watch screenshot saved locally.');
    }
  } finally {
    clearTimeout(watchdog);
    if (stream) stream.destroy();
    await delay(100);
  }
})().catch(error => { console.error(error.message); process.exitCode = 1; });
