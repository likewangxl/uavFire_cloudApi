import { reactive } from 'vue'
const initial={step:1,deliveryState:'待完善',approvalStatus:'未提交',approvalNote:'',payload:'灭火物资 · 示例载荷',prepared:false,deliveryType:'灭火投送'}
export const operations=reactive({...initial})
export function resetOperations(){Object.assign(operations,initial)}
