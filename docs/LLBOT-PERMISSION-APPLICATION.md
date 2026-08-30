# LLBot 权限申请说明

下面内容可以直接发给 LLBot 管理员。提交前请补充机器人 QQ、目标群和联系人。

## 可直接发送的申请文本

```text
您好，我申请使用 LLBot 为一个自建 Minecraft 服务器提供 QQ 群账号绑定与登录门禁。

项目名称：MinecraftQQBridge-Bungee
源码地址：https://github.com/aojiangQAQ/MinecraftQQBridge-Bungee
部署方式：自建 Windows VPS，本机 NTQQ + LLBot + BungeeCord，不提供托管机器人服务。
机器人 QQ：<请填写>
目标 QQ 群：<请填写；仅处理配置白名单内的群>

主要用途：
1. 群成员发送“绑定 <Minecraft ID>”，建立 QQ 与 Minecraft ID 的一对一绑定。
2. BungeeCord 在玩家登录时异步查询本地 SQLite；未绑定玩家拒绝进入，已绑定玩家继续登录。
3. 群成员查询自己的绑定和服务器在线状态；授权管理员可以按 QQ 或 ID 解绑。
4. 可选功能包括入群欢迎、离群自动解绑、绑定后修改群名片、入群验证码。

需要接收的 OneBot 事件：
- 指定群的群消息事件；
- group_increase / group_decrease 群成员变动事件；
- heartbeat 元事件，用于检测连接状态和自动重连。

需要调用的 OneBot 动作：
- send_group_msg：回复命令、欢迎语和验证提示；
- set_group_card：仅在用户绑定成功且管理员开启该功能时修改该用户群名片；
- set_group_kick：仅在管理员显式开启入群验证及超时踢出时使用，可按审核要求关闭；
- get_status：管理员诊断 LLBot 运行状态。

使用限制与安全措施：
- 不处理私聊，不主动添加好友或群，不群发广告，不营销，不采集群聊历史；
- 不读取或保存 QQ 密码、Cookie、二维码；
- 只保存功能所需的 QQ 号、Minecraft ID、绑定时间和审计记录，数据仅在本机 SQLite；
- OneBot 正向 WebSocket 仅监听 127.0.0.1，并使用独立强随机 Bearer Token；
- HTTP、反向 WebSocket 和公网访问全部关闭；
- 只响应白名单群内的明确命令，管理员操作还需要配置中的 QQ 白名单；
- 账号登录和扫码由账号持有人本人完成。

如果 set_group_kick 或群名片权限不符合审核要求，我可以关闭对应可选功能，只保留绑定、查询、状态和消息回复。请协助审核并告知还需要提供哪些材料，谢谢。
```

## 审核材料清单

- GitHub 仓库地址
- GitHub Release 的 JAR、版本号和 SHA-256
- 机器人 QQ 与目标群号
- 一张 LLBot 配置截图：Token 必须打码，只显示 `127.0.0.1:3001`
- 一张群内命令演示截图：避免出现无关成员 QQ、聊天记录和隐私信息
- 一张 BungeeCord 状态截图：显示数据库健康和 OneBot 已连接，不显示 Token
- 联系方式与服务器用途说明

## 最小权限替代方案

若管理员只批准基础消息能力，可使用以下配置：

- 保留：群消息事件、`send_group_msg`、heartbeat、`get_status`
- 可关闭：`group-events.group-card.enabled`
- 可关闭：`group-events.verification.mode`，从而不调用 `set_group_kick`
- 可关闭：`group-events.leave-unbind.enabled`，从而不依赖离群通知

基础绑定、查询和登录门禁仍可工作。
