export function getResponseHeader(response, name) {
    const headers = response?.headers;
    if (!headers) return null;
    if (typeof headers.get === 'function') return headers.get(name);
    return headers[name] ?? headers[name.toLowerCase()] ?? null;
}

export function protocolError(message, response) {
    const error = new Error(message);
    error.code = 'ERROR008';
    if (Number.isInteger(response?.status)) error.status = response.status;
    return error;
}

export function assertJsonResponse(response, operation) {
    const contentType = getResponseHeader(response, 'content-type');
    if (!contentType || /(?:application|text)\/(?:[\w.-]+\+)?json\b/i.test(contentType)) return;

    const routingHint = /html/i.test(contentType)
        ? '; check that /tplanner API routes are proxied before the SPA fallback'
        : '';
    throw protocolError(
        `${operation} returned ${contentType} instead of JSON (HTTP ${response.status})${routingHint}`,
        response,
    );
}

export async function readJsonResponse(response, operation) {
    assertJsonResponse(response, operation);
    try {
        return await response.json();
    } catch (cause) {
        const error = protocolError(
            `${operation} returned invalid JSON (HTTP ${response.status})`,
            response,
        );
        error.cause = cause;
        throw error;
    }
}
