function Settings() {
  return <Page><Section title="STRIDE Sense 2 probe">
    <Text>First verify live readings in the developer console. Upload is optional.</Text>
    <TextInput settingsKey="endpoint" label="Lab HR endpoint" title="Receiver URL" placeholder="http://treadmill-ip:8780/api/hr" />
    <TextInput settingsKey="token" label="Lab pairing token" title="Bearer token" />
    <Text>Use your own local lab receiver. This probe has no motor controls.</Text>
  </Section></Page>;
}
registerSettingsPage(Settings);
