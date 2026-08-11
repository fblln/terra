/**
 * Minimal Node-RED settings.
 *
 * `httpAdminRoot` moves the editor out of the way so the catch-all HTTP-in node can
 * own `/` — the mock has to answer whatever path the service under test calls, which
 * is the root of the namespace, not a corner of it.
 *
 * `functionGlobalContext` is what lets the flow hold no logic: the function node is
 * one line, and the code it calls lives in simulator.js where it can be reviewed.
 */
module.exports = {
    uiPort: 1880,
    flowFile: 'flows.json',
    httpAdminRoot: '/editor',        // the visual editor, if you want to look
    httpNodeRoot: '/',               // everything else is the mock
    functionGlobalContext: {
        sim: require('/data/simulator.js'),
    },
    logging: { console: { level: 'info', metrics: false, audit: false } },
    editorTheme: { tours: false },
};
