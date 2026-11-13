#!/usr/bin/env bash
# ==============================================================================
# check-no-real-endpoints.sh
#
# 安全门禁检查：防止源码与文档中硬编码真实服务器地址、口令或密钥。
#
# 检查项：
# a. mirage://…@HOST 节点 URI（必须为 RFC 5737/3849 文档占位或保留域名）
# b. 环境变量或默认值回退为公网 IPv4/IPv6 或长十六进制口令串 (>=24位)
# c. 源码与文档中出现的公网 IPv4/IPv6 字面量（非保留/文档段且未列入 allowlist）
#
# 排除项说明：
# - *.lock (依赖锁文件)
# - *.dat (二进制规则集数据库)
# - 二进制文件 (由 git grep -I 自动跳过)
# - native/mirage-core/src/vendor/ (只读镜像上游代码)
# - target/ (编译产物)
# - CHANGELOG.md (历史发布说明段落，包含历史上报的 AS906 测速网段等审计记录)
# ==============================================================================
set -euo pipefail

REPO_ROOT="$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
cd "$REPO_ROOT"

ALLOWLIST_FILE="scripts/endpoint-allowlist.txt"
if [[ ! -f "$ALLOWLIST_FILE" ]]; then
    echo "[-] 错误: 白名单文件 $ALLOWLIST_FILE 不存在" >&2
    exit 1
fi

PATH_EXCLUDES=(
    ':!*.lock'
    ':!*.dat'
    ':!native/mirage-core/src/vendor/**'
    ':!target/**'
    ':!CHANGELOG.md'
    ':!scripts/endpoint-allowlist.txt'
    ':!scripts/check-no-real-endpoints.sh'
)

# 加载白名单至关联数组
declare -A ALLOWED_MAP=()
while IFS= read -r line || [[ -n "$line" ]]; do
    line="${line%%#*}"
    line="$(echo "$line" | tr -d '[:space:]')"
    [[ -z "$line" ]] && continue
    ALLOWED_MAP["$line"]=1
    lower="$(echo "$line" | tr '[:upper:]' '[:lower:]')"
    ALLOWED_MAP["$lower"]=1
done < "$ALLOWLIST_FILE"

is_public_ipv4() {
    local ip="$1"
    local a b c d
    IFS=. read -r a b c d <<< "$ip"
    [[ "$a" =~ ^[0-9]+$ && "$b" =~ ^[0-9]+$ && "$c" =~ ^[0-9]+$ && "$d" =~ ^[0-9]+$ ]] || return 1
    (( a <= 255 && b <= 255 && c <= 255 && d <= 255 )) || return 1

    # 0.0.0.0/8
    (( a == 0 )) && return 1
    # 10.0.0.0/8
    (( a == 10 )) && return 1
    # 100.64.0.0/10 (RFC 6598 CGNAT)
    (( a == 100 && b >= 64 && b <= 127 )) && return 1
    # 127.0.0.0/8
    (( a == 127 )) && return 1
    # 169.254.0.0/16
    (( a == 169 && b == 254 )) && return 1
    # 172.16.0.0/12
    (( a == 172 && b >= 16 && b <= 31 )) && return 1
    # 192.0.0.0/24 (IETF Protocol Assignments)
    (( a == 192 && b == 0 && c == 0 )) && return 1
    # 192.0.2.0/24 (RFC 5737 TEST-NET-1)
    (( a == 192 && b == 0 && c == 2 )) && return 1
    # 192.168.0.0/16
    (( a == 192 && b == 168 )) && return 1
    # 198.18.0.0/15 (RFC 2544 Benchmarking / Fake-IP)
    (( a == 198 && (b == 18 || b == 19) )) && return 1
    # 198.51.100.0/24 (RFC 5737 TEST-NET-2)
    (( a == 198 && b == 51 && c == 100 )) && return 1
    # 203.0.113.0/24 (RFC 5737 TEST-NET-3)
    (( a == 203 && b == 0 && c == 113 )) && return 1
    # 224.0.0.0/4 (Multicast)
    (( a >= 224 && a <= 239 )) && return 1
    # 240.0.0.0/4 (Reserved / Bogon / Broadcast 255.255.255.255)
    (( a >= 240 )) && return 1

    return 0
}

is_public_ipv6() {
    local raw="$1"
    local ip="$raw"
    # 剥离方括号及可选端口: [2001:db8::1]:443 或 [2001:db8::1] -> 2001:db8::1
    if [[ "$ip" =~ ^\[([0-9a-fA-F:]+)\](:[0-9]+)?$ ]]; then
        ip="${BASH_REMATCH[1]}"
    fi
    # 剥离 CIDR 前缀长度 /32, /128 等
    ip="${ip%%/*}"
    ip="$(echo "$ip" | tr '[:upper:]' '[:lower:]')"

    # 白名单直接放行 (检查原始串与去括号转小写后的串)
    [[ -n "${ALLOWED_MAP[$raw]:-}" || -n "${ALLOWED_MAP[$ip]:-}" ]] && return 1

    # 禁止连续 3 个及以上冒号 (非法语法)
    [[ "$ip" == *":::"* ]] && return 1

    # :: 与 ::1
    if [[ "$ip" == "::" || "$ip" == "::1" ]]; then
        return 1
    fi

    # 检查是否包含双冒号 ::
    local left right
    local -a left_words=() right_words=()
    local has_double_colon=0
    local v4_embedded=""

    if [[ "$ip" == *"::"* ]]; then
        has_double_colon=1
        left="${ip%%::*}"
        right="${ip#*::}"
        # 只能有一个 ::
        [[ "$right" == *"::"* ]] && return 1
    else
        left="$ip"
        right=""
    fi

    # 检查末尾是否内嵌 IPv4 (如 ::ffff:192.0.2.1 或 0:0:0:0:0:ffff:192.0.2.1)
    if [[ "$right" =~ (.*:)?([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3})$ ]]; then
        v4_embedded="${BASH_REMATCH[2]}"
        local prefix="${BASH_REMATCH[1]}"
        right="${prefix%:}"
    elif [[ $has_double_colon -eq 0 && "$left" =~ (.*:)?([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3})$ ]]; then
        v4_embedded="${BASH_REMATCH[2]}"
        local prefix="${BASH_REMATCH[1]}"
        left="${prefix%:}"
    fi

    if [[ -n "$left" ]]; then
        IFS=: read -r -a left_words <<< "$left"
    fi
    if [[ -n "$right" ]]; then
        IFS=: read -r -a right_words <<< "$right"
    fi

    local total_words=$(( ${#left_words[@]} + ${#right_words[@]} ))
    local expected_words=8
    if [[ -n "$v4_embedded" ]]; then
        expected_words=6
    fi

    if [[ $has_double_colon -eq 1 ]]; then
        (( total_words <= expected_words - 1 )) || return 1
    else
        (( total_words == expected_words )) || return 1
    fi

    # 至少有一段十六进制长度 >= 2 位 (防止纯单字母噪声如 a:b:c)
    local has_2plus_hex=0
    for w in "${left_words[@]}" "${right_words[@]}"; do
        [[ -z "$w" ]] && continue
        [[ "$w" =~ ^[0-9a-fA-F]{1,4}$ ]] || return 1
        (( ${#w} >= 2 )) && has_2plus_hex=1
    done
    if [[ -n "$v4_embedded" ]]; then
        has_2plus_hex=1
    fi
    (( has_2plus_hex == 1 )) || return 1

    local -a words=(0 0 0 0 0 0 0 0)
    local idx=0
    for w in "${left_words[@]}"; do
        [[ -z "$w" ]] && continue
        words[idx]=$(( 16#$w ))
        (( idx++ ))
    done

    if [[ $has_double_colon -eq 1 ]]; then
        local missing=$(( expected_words - total_words ))
        (( idx += missing ))
    fi

    for w in "${right_words[@]}"; do
        [[ -z "$w" ]] && continue
        words[idx]=$(( 16#$w ))
        (( idx++ ))
    done

    if [[ -n "$v4_embedded" ]]; then
        local a b c d
        IFS=. read -r a b c d <<< "$v4_embedded"
        [[ "$a" =~ ^[0-9]+$ && "$b" =~ ^[0-9]+$ && "$c" =~ ^[0-9]+$ && "$d" =~ ^[0-9]+$ ]] || return 1
        (( a <= 255 && b <= 255 && c <= 255 && d <= 255 )) || return 1
        words[6]=$(( (a << 8) | b ))
        words[7]=$(( (c << 8) | d ))
    fi

    local w0=${words[0]} w1=${words[1]} w2=${words[2]} w3=${words[3]}
    local w4=${words[4]} w5=${words[5]} w6=${words[6]} w7=${words[7]}

    # 1. ::1 (Loopback) 与 :: (Unspecified)
    if (( w0 == 0 && w1 == 0 && w2 == 0 && w3 == 0 && w4 == 0 && w5 == 0 && w6 == 0 && (w7 == 0 || w7 == 1) )); then
        return 1
    fi

    # 2. RFC 3849 documentation prefix 2001:db8::/32
    if (( w0 == 0x2001 && w1 == 0x0db8 )); then
        return 1
    fi

    # 3. fe80::/10 (Link-Local)
    if (( (w0 & 0xffc0) == 0xfe80 )); then
        return 1
    fi

    # 4. fc00::/7 (Unique Local Address ULA: fc00.. - fdff..)
    if (( (w0 & 0xfe00) == 0xfc00 )); then
        return 1
    fi

    # 5. ff00::/8 (Multicast)
    if (( (w0 & 0xff00) == 0xff00 )); then
        return 1
    fi

    # 6. IPv4-mapped (::ffff:0:0/96)
    if (( w0 == 0 && w1 == 0 && w2 == 0 && w3 == 0 && w4 == 0 && w5 == 0xffff )); then
        local v4
        if [[ -n "$v4_embedded" ]]; then
            v4="$v4_embedded"
        else
            v4="$(( w6 >> 8 )).$(( w6 & 0xff )).$(( w7 >> 8 )).$(( w7 & 0xff ))"
        fi
        if [[ -n "${ALLOWED_MAP[$v4]:-}" ]] || ! is_public_ipv4 "$v4"; then
            return 1
        fi
    fi

    return 0
}

FAILED=0

# ── 检查 a: mirage://…@HOST 节点链接必须使用文档保留地址 ──────────────────────
echo "[*] [Check A] 正在扫描 mirage:// 节点链接格式与端点..."

matches_a=$(git grep --untracked -n -I -E 'mirage://' -- "${PATH_EXCLUDES[@]}" || true)
if [[ -n "$matches_a" ]]; then
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        file="${line%%:*}"
        rest="${line#*:}"
        line_no="${rest%%:*}"
        content="${rest#*:}"

        cur="$content"
        while [[ "$cur" =~ mirage://([^@[:space:]\"\'\`<>]+)@([^:/?[:space:]\"\'\`<>\)]+|\[[^]]+\]) ]]; do
            matched_full="${BASH_REMATCH[0]}"
            host="${BASH_REMATCH[2]}"
            host="${host#[}"
            host="${host%]}"

            valid=0
            # 过滤代码模板/正则变量 (如 $host, $maskedHost, 包含正则元字符等)
            if [[ "$matched_full" =~ [\\^\(\)] || "$host" =~ ^\$ || "$host" =~ [\\^\(\)] ]]; then
                valid=1
            elif [[ "$host" == "127.0.0.1" || "$host" == "localhost" || "$host" == "::1" ]]; then
                valid=1
            elif [[ "$host" =~ ^192\.0\.2\.[0-9]+$ || "$host" =~ ^198\.51\.100\.[0-9]+$ || "$host" =~ ^203\.0\.113\.[0-9]+$ ]]; then
                valid=1
            elif [[ "$host" =~ ^2001:0*[dD][bB]8: ]]; then
                valid=1
            elif [[ "$host" =~ (^|\.)(example|example\.com|test|invalid|localhost)$ ]]; then
                valid=1
            elif [[ "$host" == "host" || "$host" == "hostonly" || "$host" == "h" || "$host" == "端口" ]]; then
                valid=1
            elif [[ -n "${ALLOWED_MAP[$host]:-}" ]]; then
                valid=1
            fi

            if [[ $valid -eq 0 ]]; then
                echo "${file}:${line_no}: [Check A] 发现未授权 mirage:// 节点主机: $host"
                FAILED=1
            fi
            cur="${cur#*"$matched_full"}"
        done
    done <<< "$matches_a"
fi

# ── 检查 b: 环境变量回退默认值是公网 IPv4 或长十六进制串 ────────────────────
echo "[*] [Check B] 正在扫描环境变量回退默认值与硬编码长口令/公网地址..."

matches_b_hex=$(git grep --untracked -n -I -E '(unwrap_or|\?:|\$\{[^}]*:-).*([0-9a-fA-F]{24,})' -- "${PATH_EXCLUDES[@]}" || true)
if [[ -n "$matches_b_hex" ]]; then
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        file="${line%%:*}"
        rest="${line#*:}"
        line_no="${rest%%:*}"
        echo "${file}:${line_no}: [Check B] 命中环境变量回退或默认长口令十六进制串: $line"
        FAILED=1
    done <<< "$matches_b_hex"
fi

matches_b_ip=$(git grep --untracked -n -I -E '(unwrap_or|\?:|\$\{[^}]*:-).*([0-9]{1,3}\.){3}[0-9]{1,3}' -- "${PATH_EXCLUDES[@]}" || true)
if [[ -n "$matches_b_ip" ]]; then
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        file="${line%%:*}"
        rest="${line#*:}"
        line_no="${rest%%:*}"
        content="${rest#*:}"

        cur="$content"
        while [[ "$cur" =~ ([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}) ]]; do
            ip="${BASH_REMATCH[1]}"
            if is_public_ipv4 "$ip"; then
                if [[ -z "${ALLOWED_MAP[$ip]:-}" ]]; then
                    echo "${file}:${line_no}: [Check B] 命中环境变量回退公网 IPv4 默认值: $ip"
                    FAILED=1
                fi
            fi
            cur="${cur#*"$ip"}"
        done
    done <<< "$matches_b_ip"
fi

matches_b_ipv6=$(git grep --untracked -n -I -E '(unwrap_or|\?:|\$\{[^}]*:-).*((\[[0-9a-fA-F:]+\](:[0-9]+)?)|(\b[0-9a-fA-F]{1,4}::([0-9a-fA-F]{1,4}\b|[0-9a-fA-F]{1,4}:|[^a-zA-Z0-9_]|$))|::[0-9a-fA-F]{1,4}\b|\b([0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F:.]*)' -- "${PATH_EXCLUDES[@]}" || true)
if [[ -n "$matches_b_ipv6" ]]; then
    regex_v6="(\[[0-9a-fA-F:]+\](:[0-9]+)?|(^|[^a-zA-Z0-9_:])(([0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F:.]*|([0-9a-fA-F]{1,4}:)*::[0-9a-fA-F:.]*)([^a-zA-Z0-9_:]|$))"
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        file="${line%%:*}"
        rest="${line#*:}"
        line_no="${rest%%:*}"
        content="${rest#*:}"

        cur="$content"
        while [[ "$cur" =~ $regex_v6 ]]; do
            matched="${BASH_REMATCH[0]}"
            b1="${BASH_REMATCH[1]}"
            b4="${BASH_REMATCH[4]}"
            [[ -z "$matched" ]] && break
            if [[ "$b1" == "["* ]]; then
                token="$b1"
            else
                token="$b4"
            fi
            if is_public_ipv6 "$token"; then
                token_clean="${token%%/*}"
                if [[ -z "${ALLOWED_MAP[$token]:-}" && -z "${ALLOWED_MAP[$token_clean]:-}" ]]; then
                    echo "${file}:${line_no}: [Check B] 命中环境变量回退公网 IPv6 默认值: $token"
                    FAILED=1
                fi
            fi
            cur="${cur#*"$matched"}"
        done
    done <<< "$matches_b_ipv6"
fi

# ── 检查 c: 源码与文档中的公网 IPv4/IPv6 字面量 ──────────────────────────────
echo "[*] [Check C] 正在扫描源码与文档中的未授权公网 IPv4/IPv6 字面量..."

matches_c=$(git grep --untracked -n -I -E '([0-9]{1,3}\.){3}[0-9]{1,3}' -- "${PATH_EXCLUDES[@]}" || true)
if [[ -n "$matches_c" ]]; then
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        file="${line%%:*}"
        rest="${line#*:}"
        line_no="${rest%%:*}"
        content="${rest#*:}"

        cur="$content"
        while [[ "$cur" =~ ([0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}) ]]; do
            ip="${BASH_REMATCH[1]}"
            if is_public_ipv4 "$ip"; then
                if [[ -z "${ALLOWED_MAP[$ip]:-}" ]]; then
                    echo "${file}:${line_no}: [Check C] 发现未在白名单中的公网 IPv4 字面量: $ip"
                    FAILED=1
                fi
            fi
            cur="${cur#*"$ip"}"
        done
    done <<< "$matches_c"
fi

matches_c_ipv6=$(git grep --untracked -n -I -E '\[[0-9a-fA-F:]*:[0-9a-fA-F:]*\](:[0-9]+)?|\b[0-9a-fA-F]{1,4}::([0-9a-fA-F]{1,4}\b|[0-9a-fA-F]{1,4}:|[^a-zA-Z0-9_]|$)|::[0-9a-fA-F]{1,4}\b|\b([0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F:.]*' -- "${PATH_EXCLUDES[@]}" || true)
if [[ -n "$matches_c_ipv6" ]]; then
    regex_v6="(\[[0-9a-fA-F:]+\](:[0-9]+)?|(^|[^a-zA-Z0-9_:])(([0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F:.]*|([0-9a-fA-F]{1,4}:)*::[0-9a-fA-F:.]*)([^a-zA-Z0-9_:]|$))"
    while IFS= read -r line; do
        [[ -z "$line" ]] && continue
        file="${line%%:*}"
        rest="${line#*:}"
        line_no="${rest%%:*}"
        content="${rest#*:}"

        cur="$content"
        while [[ "$cur" =~ $regex_v6 ]]; do
            matched="${BASH_REMATCH[0]}"
            b1="${BASH_REMATCH[1]}"
            b4="${BASH_REMATCH[4]}"
            [[ -z "$matched" ]] && break
            if [[ "$b1" == "["* ]]; then
                token="$b1"
            else
                token="$b4"
            fi
            if is_public_ipv6 "$token"; then
                token_clean="${token%%/*}"
                if [[ -z "${ALLOWED_MAP[$token]:-}" && -z "${ALLOWED_MAP[$token_clean]:-}" ]]; then
                    echo "${file}:${line_no}: [Check C] 发现未在白名单中的公网 IPv6 字面量: $token"
                    FAILED=1
                fi
            fi
            cur="${cur#*"$matched"}"
        done
    done <<< "$matches_c_ipv6"
fi

if [[ $FAILED -eq 0 ]]; then
    echo "[✓] 扫描通过：未发现泄露真实服务器地址、口令或未授权公网端点 (0 命中)。"
    exit 0
else
    echo "[-] 扫描失败：检测到潜在敏感端点或未加白公网地址，请按规则处理。" >&2
    exit 1
fi
