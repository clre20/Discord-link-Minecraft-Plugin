# DiscordLink

DiscordLink 是 Paper 伺服器的 Discord 帳號綁定插件。玩家在遊戲取得 5 位數代碼，到 Discord 驗證頻道輸入即可完成綁定；可設定成「未綁定不能進服」。

## 功能特性

* **兩種模式**：
  * **強制綁定**：未綁定的玩家連線時被擋下，畫面直接顯示代碼。
  * **自由綁定**：玩家可以進服，用 `/dclink link` 取得代碼。
* **防止亂猜代碼**：同一個 Discord 帳號輸錯太多次會暫停一段時間（可設定）。
* **免驗證名單**：指定玩家不需綁定（支援從未進過伺服器的玩家）。
* **Discord 卡片與斜線指令**：綁定成功／失敗卡片、私訊通知、管理員日誌；管理員可用 `/querydc`、`/querymc` 查詢。
* **HTTP API（經 HttpHub）**：讓網站或機器人查詢綁定資料，與其他插件共用同一個端口。
* **所有文字可改**：遊戲訊息、斷線畫面、Discord 卡片都在 `messages.yml`，支援 MiniMessage 與 `&` 顏色碼。
* **設定自動補齊**：更新後自動補上新項目並保留原本的值；舊版設定會自動搬到新格式。
* **版本檢查**：啟動時檢查 GitHub 新版本；有新版時管理員登入 3 秒後會收到提醒。
* **輕量**：綁定資料放在記憶體，資料庫在背景寫入；Discord 連線不會卡住伺服器啟動。

## 支援環境

* Paper 1.21.11、26.x（Purpur 等 Paper 分支理論上相容；不支援 Spigot、Folia）
* Java 21 以上（26.x 伺服器需要 Java 25）
* 選用：[HttpHub](https://github.com/clre20/HttpHub)（要用 HTTP API 時才需要）

## 指令與權限

> 主指令 `/discordlink`，別名 `/dclink`

| 指令 | 說明 | 執行者 | 權限 | 預設 |
| :--- | :--- | :--- | :--- | :--- |
| `/dclink help` | 指令說明 | 玩家／後台 | `discordlink.help` | 所有人 |
| `/dclink link` | 取得綁定代碼 | 玩家 | `discordlink.use` | 所有人 |
| `/dclink version` | 檢查更新 | 玩家／後台 | `discordlink.version` | OP |
| `/dclink admin query <玩家\|DiscordID>` | 查詢綁定 | 玩家／後台 | `discordlink.admin` | OP |
| `/dclink admin unbind <玩家\|DiscordID>` | 解除綁定 | 玩家／後台 | `discordlink.admin` | OP |
| `/dclink admin list [頁碼]` | 綁定清單 | 玩家／後台 | `discordlink.admin` | OP |
| `/dclink bypass add <玩家>` | 加入免驗證名單 | 玩家／後台 | `discordlink.admin` | OP |
| `/dclink bypass remove <玩家>` | 移出免驗證名單 | 玩家／後台 | `discordlink.admin` | OP |
| `/dclink bypass list` | 免驗證名單 | 玩家／後台 | `discordlink.admin` | OP |
| `/dclink reload` | 重新載入設定（Token 有變會重新連線） | 玩家／後台 | `discordlink.admin` | OP |

> 有 `discordlink.version` 的玩家，在有新版本時登入 3 秒後會收到提醒。

### Discord 斜線指令

> 只有具備「管理員」或「管理伺服器」權限的成員能用，回覆只有自己看得到。

| 指令 | 說明 | 參數 |
| :--- | :--- | :--- |
| `/querydc` | 用 Minecraft 玩家查詢綁定的 Discord | `player`：玩家名稱或 UUID |
| `/querymc` | 用 Discord 用戶查詢綁定的 Minecraft 帳號 | `user`：Discord ID 或提及 |

## 使用範例

### 1. 第一次設定

1. 在 [Discord Developer Portal](https://discord.com/developers/applications) 建立機器人，開啟 **Message Content Intent**，邀請進伺服器。
2. 編輯 `plugins/DiscordLink/config.yml`：

```yaml
discord:
  token: "你的機器人 Token"
  verify-channel-id: "驗證頻道 ID"
  admin-log:
    channel-id: "管理員日誌頻道 ID"
```

3. 執行 `/dclink reload`（或重啟伺服器）。

### 2. 玩家綁定

```bash
# 自由綁定模式：玩家在遊戲內輸入
/dclink link
# 強制綁定模式：玩家連線時畫面就會顯示代碼
# 接著把 5 位數代碼貼到 Discord 驗證頻道
```

### 3. 管理

```bash
/dclink admin query Steve
/dclink admin unbind 123456789012345678
/dclink bypass add Notch
```

## HTTP API

需要安裝 [HttpHub](https://github.com/clre20/HttpHub)。API 跟其他插件共用 HttpHub 的端口（預設 `52010`），DiscordLink 不再自己開端口。

* **網址**：`http://<伺服器IP>:<HttpHub 端口>/discordlink/api/query`
* **方法**：`GET`
* **金鑰**：第一次啟動自動產生並寫入 `config.yml` 的 `api.key`
  * 標頭（建議）：`Authorization: Bearer <金鑰>`
  * 網址參數：`?key=<金鑰>`（可用 `api.allow-query-key: false` 關閉）

| action | 參數 | 說明 |
| :--- | :--- | :--- |
| `query_dc` | `target`：玩家名稱或 UUID | Minecraft 查 Discord |
| `query_mc` | `target`：Discord ID | Discord 查 Minecraft |
| `all` | — | 所有綁定 |

回應範例（`query_dc`）：

```json
{
  "status": "success",
  "bound": true,
  "data": { "player": "Steve", "uuid": "8667ba71-b85a-4004-af54-4b3a95970922", "discord_id": "123456789012345678" }
}
```

Python 範例：

```python
import requests

API_URL = "http://localhost:52010/discordlink/api/query"
API_KEY = "config.yml 裡的 api.key"
headers = {"Authorization": f"Bearer {API_KEY}"}

print(requests.get(API_URL, headers=headers, params={"action": "all"}).json())
print(requests.get(API_URL, headers=headers, params={"action": "query_dc", "target": "Steve"}).json())
print(requests.get(API_URL, headers=headers, params={"action": "query_mc", "target": "123456789012345678"}).json())
```

> 從 1.6.x 升級：舊的 `api.port`（25580）已移除，請改用上面的網址。

## 設定檔

| 檔案 | 內容 |
| :--- | :--- |
| `config.yml` | Token、頻道、模式、綁定上限、輸錯限制、API、版本檢查 |
| `messages.yml` | 所有遊戲訊息、斷線畫面、Discord 卡片與斜線指令文字 |
| `database.db` | 綁定與免驗證名單（SQLite） |

## 開發者

```bash
mvn clean package
# 產出 target/DiscordLink-<版本>.jar
```

---

# DiscordLink (English)

Discord account linking for Paper servers. Players get a 5-digit code in game and type it in a Discord channel to link. Optionally block unlinked players from joining.

## Features

* **Two modes**: forced linking (code shown on the disconnect screen) or free linking (`/dclink link`).
* **Guess protection**: a Discord account that enters too many wrong codes is paused for a while.
* **Bypass list**, **Discord embeds**, **admin slash commands** (`/querydc`, `/querymc`).
* **HTTP API via HttpHub**: shares one HTTP port with other plugins: `/discordlink/api/query`.
* **Editable text** in `messages.yml`; config files fill in missing keys automatically.
* **Update checker** on startup, `/dclink version`, and a join reminder for admins.

## Commands & Permissions

| Command | Description | Permission | Default |
| :--- | :--- | :--- | :--- |
| `/dclink link` | Get a link code | `discordlink.use` | everyone |
| `/dclink version` | Check for updates | `discordlink.version` | op |
| `/dclink admin <query\|unbind\|list>` | Manage links | `discordlink.admin` | op |
| `/dclink bypass <add\|remove\|list>` | Bypass list | `discordlink.admin` | op |
| `/dclink reload` | Reload config | `discordlink.admin` | op |
