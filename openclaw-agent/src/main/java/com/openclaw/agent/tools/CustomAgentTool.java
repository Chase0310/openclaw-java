package com.openclaw.agent.tools;

/**
 * 自定义业务工具标记接口。
 * 只有实现了该接口的工具，才会被 AgentBeanConfig 自动收集并注册到 ToolRegistry。
 */
public interface CustomAgentTool extends AgentTool {
}
