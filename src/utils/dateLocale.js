import { enUS, zhCN } from 'date-fns/locale';

export const getDateLocale = (language) => {
    switch (language) {
        case 'zh':
        case 'zh-CN':
            return zhCN;
        default:
            return enUS;
    }
};
