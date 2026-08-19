// 命令仓库(见 docs/sync-v3.md §9/§16):UI 的所有写操作经此产生命令。
// submit:先持久化进 outbox,再乐观归约到展示状态 —— UI 从不直接改中央镜像。
import { appendCommands } from './commandOutbox';
import { applyCommand, emptyState } from './localReducer';

export function createCommandRepository({ store, getDisplay, setDisplay }) {
    return {
        /** 提交一条语义命令:本地持久化 → 乐观应用到展示状态。 */
        async submit(command) {
            const [stamped] = await appendCommands(store, [command]);
            const current = (await getDisplay()) ?? emptyState();
            const result = applyCommand(current, stamped, stamped.clientSequence);
            if (result.state !== current) await setDisplay(result.state);
            return stamped;
        },
    };
}
