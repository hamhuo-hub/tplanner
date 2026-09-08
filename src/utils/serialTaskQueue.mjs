/** One adapter's read/diff/append/install cycle must finish before the next starts. */
export function createSerialTaskQueue() {
    let tail = Promise.resolve();
    return function enqueue(operation) {
        const result = tail.then(operation);
        // The caller still receives the failure; later saves must not inherit it.
        tail = result.then(() => undefined, () => undefined);
        return result;
    };
}
