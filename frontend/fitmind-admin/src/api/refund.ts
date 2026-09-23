/**
 * 退款审批（fm-trade，网关前缀 /ts）
 *
 * ★★ `/ts/admin/**` 是**真的校验管理员**的（实测：学员 token → 403）。
 *   它是本项目里权限边界最完整的一类接口，与 #A9 那批形成对照。
 */
import type {
  ApproveForm,
  PageDTO,
  RefundApplyPageQuery,
  RefundApplyPageVO,
  RefundApplyVO,
} from '@/types/admin'
import { http } from '@/utils/request'

/** 退款申请分页（管理端；`refundStatus=1` 只看待审批） */
export function getRefundPage(query: RefundApplyPageQuery): Promise<PageDTO<RefundApplyPageVO>> {
  return http.get<PageDTO<RefundApplyPageVO>>('/ts/admin/refund-apply/page', { params: query })
}

/** 退款详情（管理端） */
export function getRefundDetail(id: string): Promise<RefundApplyVO> {
  return http.get<RefundApplyVO>(`/ts/admin/refund-apply/${id}`)
}

/**
 * 下一个待审批的申请（逐条审批用）
 *
 * ★ 返回 `null` 表示"没有待审批的了" —— 这不是错误，页面要显示"已全部处理完"。
 */
export function getNextToApprove(): Promise<RefundApplyVO | null> {
  return http.get<RefundApplyVO | null>('/ts/admin/refund-apply/next', { silent: true })
}

/**
 * 审批（同意 / 拒绝）
 *
 * ★★ 同意之后是**异步链路**：调渠道 → 渠道回调 → 改单 → 发 MQ → 撤课表。
 *   所以"同意"返回成功**不代表退款已到账** —— 页面必须接着轮询状态，
 *   等到 `status=5`（退款成功）或 `6`（退款失败）才算有结论。
 *   断言"点完就成功"是本项目最容易犯的一类假绿（M7-3 已踩过）。
 */
export function approveRefund(form: ApproveForm): Promise<void> {
  return http.put<void>('/ts/admin/refund-apply/approval', form)
}

/** 管理员直接退款（跳过审批） */
export function directRefund(orderDetailId: string, reason?: string): Promise<string> {
  return http.post<string>('/ts/admin/refund-apply/direct', null, {
    params: { orderDetailId, reason },
  })
}
