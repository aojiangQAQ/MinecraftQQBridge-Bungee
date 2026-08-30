# 部署与回滚

本文只描述 BungeeCord 代理端。不要把本 JAR 放入 Bukkit/Spigot 子服，也不要让
子服直接读写 `bridge.db`。

## 部署前检查

- 准备可用的 BungeeCord 与 Java 8 运行环境
- 安装 LLBot/NTQQ，由 QQ 账号持有人完成扫码登录
- 生成至少 32 字符的独立随机 Token
- 确认 OneBot WebSocket 和 WebUI 仅监听 `127.0.0.1`
- 确认 Bungee 后端端口不能被公网直接访问
- 备份 BungeeCord JAR、`plugins`、代理配置、旧数据库和启动脚本

## LLBot 最小配置

在 LLBot 中只启用 OneBot 11 正向 WebSocket：

```json
{
  "webui": {
    "enable": true,
    "host": "127.0.0.1",
    "port": 3080
  },
  "ob11": {
    "enable": true,
    "connect": [
      {
        "type": "ws",
        "enable": true,
        "host": "127.0.0.1",
        "port": 3001,
        "heartInterval": 60000,
        "token": "__GENERATE_A_RANDOM_TOKEN_OF_AT_LEAST_32_CHARACTERS__",
        "reportSelfMessage": false,
        "reportOfflineMessage": false,
        "messageFormat": "array",
        "debug": false
      }
    ]
  }
}
```

关闭 HTTP、反向 WebSocket 和 HTTP POST。不要在截图、工单、聊天或仓库中发送
Token、Cookie、二维码和运行日志。

## 插件配置

1. 停止 BungeeCord。
2. 把发布 JAR 放入代理端 `plugins`。
3. 启动一次生成 `plugins/MinecraftQQBridge-Bungee/config.yml`，然后停止代理。
4. 填写 OneBot、允许群和管理员：

```yaml
onebot:
  enabled: true
  url: "ws://127.0.0.1:3001/"
  token: "__SAME_RANDOM_TOKEN_AS_LLBOT__"

groups:
  allowed:
    - "__TARGET_GROUP_ID__"

admins:
  user-ids:
    - "__ADMIN_QQ_ID__"
```

QQ 号和群号必须写成带引号的数字字符串。插件会拒绝非回环 OneBot 地址，也会
拒绝长度或字符不合规的 Token。

## 旧数据库迁移

插件依次寻找：

- `plugins/mcqqrun/whitelist.db`
- `plugins/mcqqrun-BungeeCord/whitelist.db`
- 新插件数据目录中的 `whitelist.db`

迁移前必须停服。迁移器会先复制原数据库及 `-wal`、`-shm`、`-journal`，从检查
副本读取，绝不修改原库。玩家名按 ASCII 小写规范化；QQ 或玩家名冲突的相关行
全部隔离，等待管理员人工处理。同一来源内容重复启动为幂等操作。

## 启动验收

启动 BungeeCord 后执行：

```text
/mcqqbridge status
/mcqqbridge dbcheck
/mcqqbridge migration
```

逐项验证：

1. OneBot 显示 `CONNECTED`，心跳为健康状态。
2. 群内绑定成功，数据库重启后仍保留记录。
3. 未绑定测试名被代理拒绝。
4. 已绑定测试名通过 QQ 门禁并进入后续登录流程。
5. 管理员解绑后，该玩家立即恢复为拒绝状态。
6. 欢迎、退群解绑、群名片和验证码只在对应配置开启时生效。
7. 停止代理后 WebSocket、数据库线程和连接均正常关闭。

## 回滚

1. 停止 BungeeCord 并确认代理端口退出。
2. 移出新 JAR，恢复部署前备份的旧插件和配置。
3. 保留新生成的 `bridge.db` 供问题分析，不要直接删除证据。
4. 原 `whitelist.db` 未被迁移器修改，通常无需反向转换。
5. 启动旧版本并复核代理监听、后端连接和登录行为。

## 离线模式边界

若 BungeeCord 使用 `online_mode=false`，本插件只能确认连接使用的玩家名存在绑定，
不能证明连接者拥有该 Minecraft 账号。必须继续使用 AuthMe 等登录认证，并阻止
玩家绕过代理直连后端。游戏内所有权验证属于后续 companion 范围。
