/**
 * A drop-in replacement for the WireMock admin subset terra actually uses.
 *
 * The point of the exercise: if this speaks the same four endpoints, `SimulatorProbe`
 * does not change, the demo's `Simulator.kt` does not change, and the tests do not
 * change — swapping the mock technology becomes two lines in an environment file.
 *
 *   POST /__admin/mappings                  register a rule            -> 201
 *   POST /__admin/requests/find             read the journal           -> 200
 *   POST /__admin/mappings/remove-by-metadata   drop this test's rules -> 200
 *   POST <anything else>                    traffic; match and answer
 *
 * Lives in a file rather than inside a function node's escaped JSON string so it is
 * reviewable in a diff. flows.json wires it up and holds no logic.
 */

const rules = [];      // registered mappings, newest last
const journal = [];    // every traffic request, unpruned like WireMock's
const JOURNAL_MAX = 10000;

/** `$[?(@.user == 'usr-1')]` — the one JSONPath shape terra's matchingBody produces. */
const PREDICATE = /^\$\[\?\(@\.([A-Za-z0-9_]+)\s*==\s*'([^']*)'\)\]$/;

/** `{{jsonPath request.body '$.order'}}` / the same over `originalRequest`. */
const TEMPLATE = /\{\{jsonPath\s+(request|originalRequest)\.body\s+'\$\.([A-Za-z0-9_]+)'\}\}/g;

function parseBody(raw) {
    try { return JSON.parse(raw || '{}'); } catch (e) { return {}; }
}

function render(template, body) {
    return String(template).replace(TEMPLATE, (_, __, field) => {
        const value = body[field];
        return value === undefined || value === null ? '' : String(value);
    });
}

function matches(rule, req) {
    if ((rule.request.method || 'POST') !== req.method) return false;
    if (rule.request.urlPath !== req.url) return false;

    const wanted = ((rule.request.headers || {})['X-Test-Id'] || {}).equalTo;
    if (wanted !== undefined && req.headers['x-test-id'] !== wanted) return false;

    for (const pattern of rule.request.bodyPatterns || []) {
        const expression = pattern.matchesJsonPath;
        const parsed = PREDICATE.exec(expression || '');
        // Refusing beats matching nothing: a rule that silently never fires is the
        // failure mode this whole scoping convention exists to avoid.
        if (!parsed) throw new Error('unsupported matchesJsonPath: ' + expression);
        if (String(req.body[parsed[1]]) !== parsed[2]) return false;
    }
    return true;
}

/**
 * Lowest priority number wins, as in WireMock — and on a tie the newest rule wins,
 * also as in WireMock. Two tests each registering a catch-all is the normal case, so
 * the tie-break is not a detail.
 */
function pick(req) {
    return rules
        .slice()
        .reverse()
        .filter((rule) => matches(rule, req))
        .sort((a, b) => (a.priority || 5) - (b.priority || 5))[0];
}

function webhookFrom(rule, req) {
    const action = (rule.postServeActions || []).find((a) => a.name === 'webhook');
    if (!action) return null;
    const parameters = action.parameters || {};
    return {
        url: parameters.url,
        headers: parameters.headers || {},
        payload: render(parameters.body, req.body),
        delay: ((parameters.delay || {}).milliseconds) || 0,
    };
}

function handle(msg) {
    // Node-RED's request wrapper does not promise Express's `path`, so fall back.
    const path = String(msg.req.path || msg.req.originalUrl || msg.req.url || '/').split('?')[0];
    const body = parseBody(typeof msg.payload === 'string' ? msg.payload : JSON.stringify(msg.payload));
    const request = {
        method: msg.req.method,
        url: path,
        headers: msg.req.headers || {},
        body: body,
    };

    // A liveness probe as much as an admin call: the chaos tests read this straight
    // through the proxy to prove the dependency is healthy while the caller's path
    // to it is severed.
    if (path === '/__admin/mappings' && request.method === 'GET') {
        return [{ ...msg, statusCode: 200, payload: { mappings: rules } }, null];
    }

    if (path === '/__admin/mappings' && request.method === 'POST') {
        rules.push(body);
        return [{ ...msg, statusCode: 201, payload: { registered: rules.length } }, null];
    }

    if (path === '/__admin/requests/find') {
        const found = journal.filter((entry) =>
            entry.method === (body.method || 'POST') &&
            entry.url === body.urlPath &&
            (!((body.headers || {})['X-Test-Id'] || {}).equalTo ||
                entry.headers['x-test-id'] === body.headers['X-Test-Id'].equalTo));
        return [{ ...msg, statusCode: 200, payload: { requests: found } }, null];
    }

    if (path === '/__admin/mappings/remove-by-metadata') {
        const wanted = (body.matchesJsonPath || {}).equalTo;
        for (let i = rules.length - 1; i >= 0; i--) {
            if (((rules[i].metadata || {}).testId) === wanted) rules.splice(i, 1);
        }
        return [{ ...msg, statusCode: 200, payload: { removed: wanted } }, null];
    }

    // Traffic.
    journal.push({
        method: request.method,
        url: path,
        headers: request.headers,
        body: JSON.stringify(body),        // a string, the shape terra parses
    });
    if (journal.length > JOURNAL_MAX) journal.shift();

    const rule = pick(request);
    if (!rule) return [{ ...msg, statusCode: 404, payload: { error: 'no rule matched', path } }, null];

    const response = {
        ...msg,
        statusCode: rule.response.status,
        headers: rule.response.headers || {},
        payload: render(rule.response.body, body),
    };

    const webhook = webhookFrom(rule, request);
    return [response, webhook];
}

module.exports = { handle };
