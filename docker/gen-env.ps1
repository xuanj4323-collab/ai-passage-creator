# ============================================================
# 从 Windows 用户级环境变量生成项目根目录的 .env
#
#   pwsh -File docker\gen-env.ps1
#
# 注意必须用 pwsh（PowerShell 7）执行：
# 老版 powershell.exe（5.1）默认按 GBK 读取脚本文件，
# 本文件是 UTF-8 编码，中文注释会变成乱码并引发语法错误。
# （本文件已加 BOM 兼容，但用 pwsh 最稳妥）
#
# 为什么要这一步：docker compose 只认同目录的 .env 文件，
# 而你的密钥配在 Windows 用户级环境变量里（新终端虽然能看到，
# 但 compose 的变量插值只读 .env 和当前进程环境，不读注册表）。
#
# 生成结果 .env 含明文密钥，已被 .gitignore 忽略，不要提交。
# ============================================================

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $root '.env'

# 左侧是 .env 里的变量名，右侧是 Windows 环境变量名
$mapping = [ordered]@{
    'MYSQL_ROOT_PASSWORD'   = 'Mysql_Password'
    'DS_API_KEY'            = 'DS_API_KEY'
    'Unsplash_Access_Key'   = 'Unsplash_Access_Key'
    'OSS_ACCESS_KEY_ID'     = 'OSS_ACCESS_KEY_ID'
    'OSS_ACCESS_KEY_SECRET' = 'OSS_ACCESS_KEY_SECRET'
    'ALY_API_KEY'           = 'ALY_API_KEY'
    'STRIPE_API_KEY'        = 'STRIPE_API_KEY'
    'STRIPE_WEBHOOK_SECRET' = 'STRIPE_WEBHOOK_SECRET'
}

$lines = [System.Collections.Generic.List[string]]::new()
$lines.Add('# 由 docker/gen-env.ps1 自动生成，含明文密钥，不要提交到仓库')
$lines.Add('')

Write-Host '读取 Windows 环境变量并写入 .env ...' -ForegroundColor Cyan

foreach ($key in $mapping.Keys) {
    $src = $mapping[$key]
    $value = [Environment]::GetEnvironmentVariable($src, 'User')
    if (-not $value) {
        $value = [Environment]::GetEnvironmentVariable($src, 'Process')
    }

    if ($value) {
        # 用双引号包裹，避免值里的 # 被当成注释、$ 被当成变量插值
        $escaped = $value -replace '"', '\"'
        $lines.Add("$key=`"$escaped`"")
        Write-Host ("  {0,-24} <- {1}  已设置（长度 {2}）" -f $key, $src, $value.Length) -ForegroundColor Green
    }
    else {
        $lines.Add("$key=")
        Write-Host ("  {0,-24} <- {1}  未找到，留空" -f $key, $src) -ForegroundColor Yellow
    }
}

# 前端对外地址：Stripe 支付完成后浏览器跳回的地址
# 用 compose 暴露的 8080；上线时改成正式域名
$lines.Add('')
$lines.Add('APP_BASE_URL="http://localhost:8080"')

# UTF-8 无 BOM，否则 docker compose 解析第一行会带上 BOM 导致变量名错乱
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllLines($envFile, $lines, $utf8NoBom)

Write-Host ''
Write-Host "已生成: $envFile" -ForegroundColor Cyan
Write-Host '下一步: docker compose up -d --build' -ForegroundColor Cyan
