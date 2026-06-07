// 多设备时区/时钟不一致会让 LWW（updatedAt 较大者胜出）合并失去意义——
// 时钟偏快的设备永远能覆盖时钟偏慢的设备，与编辑的实际先后顺序无关。
// 这里维护一个相对于「同步对端」的时钟偏移量：所有写入 updatedAt/deletedAt
// 时改用 now()，得到的时间戳就近似对端（最近一次同步到的服务器）的时钟，
// 从而让跨设备的时间戳具有可比性。
let offset = 0;

export function setClockOffset(o) {
    if (Number.isFinite(o)) offset = o;
}

export function getClockOffset() {
    return offset;
}

export function now() {
    return Date.now() + offset;
}
