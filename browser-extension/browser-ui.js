export function openWorkspace(api) {
  return api.tabs.create({url: api.runtime.getURL('app.html')});
}
export function openUI(api, tab) {
  // Invoke synchronously so browsers retain the originating user gesture.
  if (api.sidePanel?.open && Number.isInteger(tab?.id)) return api.sidePanel.open({tabId: tab.id});
  if (api.sidebarAction?.open) return api.sidebarAction.open();
  return openWorkspace(api);
}
export function configureAction(api) {
  if (api.sidePanel?.setPanelBehavior) {
    Promise.resolve(api.sidePanel.setPanelBehavior({openPanelOnActionClick: true})).catch(() => {
      api.action.onClicked.addListener(tab => openUI(api, tab).catch(() => openWorkspace(api)));
    });
  } else {
    api.action.onClicked.addListener(tab => Promise.resolve(openUI(api, tab)).catch(() => openWorkspace(api)));
  }
}
