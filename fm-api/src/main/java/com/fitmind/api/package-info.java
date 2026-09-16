/**
 * 服务间调用契约模块。
 *
 * <p>本模块只放「跨服务」的东西，不放任何业务实现：</p>
 * <ul>
 *     <li>{@code client/} —— OpenFeign 客户端接口（如 {@code CourseClient}、{@code UserClient}）</li>
 *     <li>{@code dto/}     —— 跨服务传输对象</li>
 *     <li>{@code client/**&#47;fallback/} —— 服务降级实现</li>
 *     <li>{@code constants/} —— 跨服务共享的常量与枚举</li>
 * </ul>
 *
 * <p>设计意义：把「谁调谁」的契约集中在一处，各业务服务只依赖本模块就能调用其他服务，
 * 不需要依赖对方的实现，从而降低耦合。</p>
 *
 * <p><b>M0 阶段</b>：本模块只搭骨架，具体 Feign 客户端随对应里程碑（M1 起）逐个补充。</p>
 */
package com.fitmind.api;
