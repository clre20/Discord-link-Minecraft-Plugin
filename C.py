import requests

# 1. 配置設定
API_URL = "http://localhost:52010/discordlink/api/query"  # HttpHub 端口
# 請替換為您 config.yml 中隨機生成的 api.key (64 hex)
API_KEY = "YOUR_GENERATED_64_HEX_API_KEY"

headers = {
    # 支援以 Bearer Token 形式通過身份驗證
    "Authorization": f"Bearer {API_KEY}"
}

def query_dc_by_mc(player_name_or_uuid):
    """
    透過 Minecraft 玩家名稱或 UUID 查詢綁定的 Discord ID
    """
    params = {
        "action": "query_dc",
        "target": player_name_or_uuid
    }
    response = requests.get(API_URL, headers=headers, params=params)
    return response.json()

def query_mc_by_dc(discord_id):
    """
    透過 Discord ID 查詢綁定的 Minecraft 帳號清單
    """
    params = {
        "action": "query_mc",
        "target": discord_id
    }
    response = requests.get(API_URL, headers=headers, params=params)
    return response.json()

def query_all_bindings():
    """
    獲取目前伺服器中所有的綁定關係
    """
    params = {
        "action": "all"
    }
    response = requests.get(API_URL, headers=headers, params=params)
    return response.json()

if __name__ == "__main__":
    # 1. 查詢所有綁定關係
    print("=== 所有綁定關係 ===")
    print(query_all_bindings())
    
    # 2. 用 Minecraft 名稱查詢 Discord
    print("\n=== 用 Minecraft 名稱查詢 Discord ===")
    print(query_dc_by_mc("Steve"))

    # 3. 用 Discord ID 查詢 Minecraft
    print("\n=== 用 Discord ID 查詢 Minecraft ===")
    print(query_mc_by_dc("123456789012345678"))