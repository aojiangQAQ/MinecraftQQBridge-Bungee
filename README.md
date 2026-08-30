# MinecraftQQBridge-Bungee

面向 BungeeCord 网络的 Minecraft 与 QQ 群账号绑定、登录门禁和群管理桥接插件。
插件通过 LLBot 提供的 OneBot 11 正向 WebSocket 工作，数据保存在代理端 SQLite
数据库中。

> 本项目是 **BungeeCord 插件**，不是 Bukkit/Spigot 插件。请勿把 JAR 放进子服
> `plugins` 目录，也不要让子服直接读写代理端数据库。

## 功能

- 指定 QQ 群内使用 `绑定 <Minecraft ID>` 建立一对一绑定
- 未绑定玩家在 BungeeCord 登录入口被拒绝，数据库检查全程异步
- 查询本人/他人绑定，管理员按 QQ 或 Minecraft ID 解绑
- SQLite 持久化、审计、数据库异常默认拒绝登录
- 入群欢迎、退群自动解绑、固定或随机验证码、绑定后修改群名片
- OneBot Bearer Token、心跳检查、请求 echo、状态监控和退避重连
- 从旧版 `whitelist(playerName,userId)` SQLite 数据库只读迁移
- 为后续 Bukkit companion 预留版本化协议，当前版本不要求子服插件

## 工作方式

```text
QQ / NTQQ
    |
  LLBot
    |  OneBot 11 forward WebSocket
    |  ws://127.0.0.1:3001 + Bearer Token
    v
MinecraftQQBridge-Bungee
    |-- SQLite: QQ <-> Minecraft ID
    |-- Bungee login access control
    `-- group commands and member events
```

插件只处理配置白名单内的群。OneBot 地址必须是回环地址；启用时 Token 必须为
32-256 个不含空白的可打印 ASCII 字符。

## LLBot 功能与权限用途

项目只使用下列 OneBot 11 能力：

| 类型 | OneBot 事件/动作 | 用途 |
| --- | --- | --- |
| 接收 | 群消息事件 | 识别绑定、查询、解绑、状态和菜单命令 |
| 接收 | `group_increase` | 发送欢迎语；可选创建入群验证 |
| 接收 | `group_decrease` | 可选按离群成员 `user_id` 解除绑定 |
| 接收 | heartbeat meta event | 检测连接健康并在超时后重连 |
| 调用 | `send_group_msg` | 回复命令、欢迎语和验证提示 |
| 调用 | `set_group_card` | 可选在绑定成功后把群名片改为配置格式 |
| 调用 | `set_group_kick` | 仅在管理员开启入群验证和超时踢出时使用 |
| 调用 | `get_status` | 管理员诊断 LLBot/OneBot 状态 |

项目不读取 QQ 密码、Cookie 或二维码，不处理私聊，不主动加好友/加群，不群发广告，
不抓取群聊历史，也不开放公网 OneBot 接口。完整申请文本见
[LLBot 权限申请说明](docs/LLBOT-PERMISSION-APPLICATION.md)。

## 兼容基线

- Java 8 字节码
- BungeeCord API `1.16-R0.4`
- 已验收代理：BungeeCord build `1877`
- LLBot 部署目标：`v8.1.9`，OneBot 11 正向 WebSocket

更高版本代理或不同 OneBot 实现应先在测试环境验证。

## 快速部署

1. 从 GitHub Releases 下载 `MinecraftQQBridge-Bungee-*.jar`。
2. 在 LLBot 中只开启 OneBot 11 正向 WebSocket，监听 `127.0.0.1:3001`，设置强随机 Token。
3. 把 JAR 放入 BungeeCord 的 `plugins` 目录并启动一次。
4. 编辑 `plugins/MinecraftQQBridge-Bungee/config.yml`：

```yaml
onebot:
  enabled: true
  url: "ws://127.0.0.1:3001/"
  token: "请替换为与 LLBot 相同的强随机 Token"

groups:
  allowed:
    - "目标群号"

admins:
  user-ids:
    - "管理员 QQ 号"
```

5. 重启 BungeeCord，执行 `/mcqqbridge status` 和 `/mcqqbridge dbcheck`。
6. 在测试群完成绑定、未绑定拒绝、已绑定放行和解绑回收测试。

部署、旧库迁移和回滚细节见 [部署文档](docs/DEPLOYMENT.md)。

## QQ 群命令

命令文本均可在 `config.yml` 修改。

| 默认命令 | 默认权限 |
| --- | --- |
| `绑定 <Minecraft ID>` | 配置允许群的成员 |
| `我的绑定` | 配置允许群的成员 |
| `他人绑定 <QQ 或 @成员>` | 由配置决定 |
| `删除ID <Minecraft ID>` | 配置中的管理员 QQ |
| `删除QQ <QQ 或 @成员>` | 配置中的管理员 QQ |
| `服务器状态` | 由配置决定 |
| `菜单` | 配置允许群的成员 |
| `验证 <验证码>` | 有待完成验证的成员 |

群主/群管理员身份不会自动获得插件管理权限。危险命令必须由
`admins.user-ids` 明确授权；也可开启 `admins.require-group-role` 进行双重校验。

## BungeeCord 管理命令

- `/mcqqbridge status`
- `/mcqqbridge reconnect`
- `/mcqqbridge dbcheck`
- `/mcqqbridge migration`

控制台默认可用；玩家需要对应权限或 `mcqqbridge.admin`。

## 构建

需要 JDK 17 运行 Maven，产物仍编译为 Java 8 字节码：

```bash
mvn clean verify
```

成品位于 `target/MinecraftQQBridge-Bungee-2.1.0.jar`。测试覆盖配置校验、SQLite
迁移、绑定约束、群事件、OneBot 鉴权/重连和插件生命周期。

## 安全与隐私

- 不要提交真实 Token、QQ 白名单、SQLite 数据库、日志、二维码或生产配置
- OneBot 和 LLBot WebUI 只监听回环地址，禁止暴露到公网
- 离线模式下，QQ 绑定只证明名字在名单中，不能替代 AuthMe 等账号认证
- 后端服务器必须阻止玩家绕过 BungeeCord 直接连接
- QQ ID 与 Minecraft ID 属于运行数据，只在本地 SQLite 中按功能所需保存

参见 [安全策略](SECURITY.md) 和 [数据与隐私说明](docs/PRIVACY.md)。

## 当前边界

一期兼容旧行为：群成员可直接声明 Minecraft ID。游戏内/AuthMe 一次性验证码
所有权验证计划由后续 Bukkit companion 完成。在此之前，离线模式服务器必须继续
使用登录插件并做好后端防直连。
