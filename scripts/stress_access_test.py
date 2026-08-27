#!/usr/bin/env python3
import json
import subprocess
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

DEVICE = "R5CX21FD9PX"
DEBUG_API = "http://127.0.0.1:9090"

# 1. 国际主流服务 (应走隧道代理)
GLOBAL_SERVICES = [
    # AI / 大模型
    {"name": "OpenAI 官网", "domain": "chatgpt.com", "expected": "proxy"},
    {"name": "OpenAI API", "domain": "api.openai.com", "expected": "proxy"},
    {"name": "Claude / Anthropic", "domain": "claude.ai", "expected": "proxy"},
    {"name": "HuggingFace", "domain": "huggingface.co", "expected": "proxy"},
    
    # 社交与通讯
    {"name": "Telegram Web", "domain": "web.telegram.org", "expected": "proxy"},
    {"name": "Twitter / X", "domain": "x.com", "expected": "proxy"},
    {"name": "Twitter API", "domain": "api.twitter.com", "expected": "proxy"},
    {"name": "Reddit", "domain": "www.reddit.com", "expected": "proxy"},
    {"name": "Discord", "domain": "discord.com", "expected": "proxy"},
    {"name": "Instagram", "domain": "www.instagram.com", "expected": "proxy"},
    {"name": "Facebook", "domain": "www.facebook.com", "expected": "proxy"},
    
    # 开发者与开源社区
    {"name": "GitHub", "domain": "github.com", "expected": "proxy"},
    {"name": "GitHub API", "domain": "api.github.com", "expected": "proxy"},
    {"name": "GitLab", "domain": "gitlab.com", "expected": "proxy"},
    {"name": "Docker Hub", "domain": "hub.docker.com", "expected": "proxy"},
    {"name": "Stack Overflow", "domain": "stackoverflow.com", "expected": "proxy"},
    {"name": "Rust Crates", "domain": "crates.io", "expected": "proxy"},
    {"name": "NPM 官方源", "domain": "registry.npmjs.org", "expected": "proxy"},
    {"name": "PyPI 官方源", "domain": "pypi.org", "expected": "proxy"},
    
    # 流媒体与音视频
    {"name": "YouTube 首页", "domain": "www.youtube.com", "expected": "proxy"},
    {"name": "YouTube 图片 CDN", "domain": "i.ytimg.com", "expected": "proxy"},
    {"name": "Netflix", "domain": "www.netflix.com", "expected": "proxy"},
    {"name": "Spotify", "domain": "open.spotify.com", "expected": "proxy"},
    {"name": "Twitch 直播", "domain": "www.twitch.tv", "expected": "proxy"},
    
    # 知识与百科/全球基础设施
    {"name": "维基百科", "domain": "www.wikipedia.org", "expected": "proxy"},
    {"name": "Cloudflare", "domain": "www.cloudflare.com", "expected": "proxy"},
    {"name": "Fastly CDN", "domain": "www.fastly.com", "expected": "proxy"},
    {"name": "Speedtest 官网", "domain": "www.speedtest.net", "expected": "proxy"},
    {"name": "DuckDuckGo 搜索", "domain": "duckduckgo.com", "expected": "proxy"},
    {"name": "Medium 博客", "domain": "medium.com", "expected": "proxy"}
]

# 2. 国内主流骨干服务 (应走直连)
DOMESTIC_SERVICES = [
    # 搜索与核心门户
    {"name": "百度搜索", "domain": "www.baidu.com", "expected": "direct"},
    {"name": "搜狗搜索", "domain": "www.sogou.com", "expected": "direct"},
    {"name": "360 搜索", "domain": "www.so.com", "expected": "direct"},
    {"name": "新浪网", "domain": "www.sina.com.cn", "expected": "direct"},
    {"name": "网易首页", "domain": "www.163.com", "expected": "direct"},
    {"name": "腾讯网", "domain": "www.qq.com", "expected": "direct"},
    
    # 电商与生活服务
    {"name": "淘宝网", "domain": "www.taobao.com", "expected": "direct"},
    {"name": "京东商城", "domain": "www.jd.com", "expected": "direct"},
    {"name": "拼多多", "domain": "www.pinduoduo.com", "expected": "direct"},
    {"name": "美团生活", "domain": "www.meituan.com", "expected": "direct"},
    {"name": "支付宝", "domain": "www.alipay.com", "expected": "direct"},
    {"name": "高德地图", "domain": "www.amap.com", "expected": "direct"},
    {"name": "携程旅行", "domain": "www.ctrip.com", "expected": "direct"},
    {"name": "顺丰速运", "domain": "www.sf-express.com", "expected": "direct"},
    
    # 视频与社交流媒体
    {"name": "哔哩哔哩", "domain": "www.bilibili.com", "expected": "direct"},
    {"name": "抖音", "domain": "www.douyin.com", "expected": "direct"},
    {"name": "快手", "domain": "www.kuaishou.com", "expected": "direct"},
    {"name": "爱奇艺", "domain": "www.iqiyi.com", "expected": "direct"},
    {"name": "优酷视频", "domain": "www.youku.com", "expected": "direct"},
    {"name": "知乎社区", "domain": "www.zhihu.com", "expected": "direct"},
    {"name": "小红书", "domain": "www.xiaohongshu.com", "expected": "direct"},
    
    # 云计算与技术社区
    {"name": "阿里云", "domain": "www.aliyun.com", "expected": "direct"},
    {"name": "腾讯云", "domain": "cloud.tencent.com", "expected": "direct"},
    {"name": "华为云", "domain": "www.huaweicloud.com", "expected": "direct"},
    {"name": "Gitee 开源", "domain": "gitee.com", "expected": "direct"},
    {"name": "CSDN 社区", "domain": "www.csdn.net", "expected": "direct"},
    {"name": "掘金社区", "domain": "juejin.cn", "expected": "direct"},
    
    # 银行金融与政企
    {"name": "中国工商银行", "domain": "www.icbc.com.cn", "expected": "direct"},
    {"name": "中国银行", "domain": "www.boc.cn", "expected": "direct"},
    {"name": "中国建设银行", "domain": "www.ccb.com", "expected": "direct"},
    {"name": "中国政府网", "domain": "www.gov.cn", "expected": "direct"},
    {"name": "清华大学", "domain": "www.tsinghua.edu.cn", "expected": "direct"},
    {"name": "铁路 12306", "domain": "www.12306.cn", "expected": "direct"}
]

def query_debug_route(domain):
    try:
        req = urllib.request.Request(
            f"{DEBUG_API}/debug/route",
            data=json.dumps({"domain": domain, "port": 443, "proto": "tcp"}).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )
        with urllib.request.urlopen(req, timeout=2) as resp:
            data = json.loads(resp.read().decode("utf-8"))
            return data.get("decision", "unknown")
    except Exception:
        return "unknown"

def query_debug_snapshot():
    try:
        with urllib.request.urlopen(f"{DEBUG_API}/debug/snapshot", timeout=2) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except Exception:
        return {}

def test_service_on_device(item):
    domain = item["domain"]
    name = item["name"]
    expected = item["expected"]
    
    route_dec = query_debug_route(domain)
    
    cmd = [
        "adb", "-s", DEVICE, "shell",
        f"curl -s -m 7 -o /dev/null -w '%{{http_code}}|%{{time_namelookup}}|%{{time_connect}}|%{{time_total}}' -I https://{domain} 2>/dev/null || "
        f"curl -s -m 7 -o /dev/null -w '%{{http_code}}|%{{time_namelookup}}|%{{time_connect}}|%{{time_total}}' -I http://{domain} 2>/dev/null || "
        "echo 'FAIL|0|0|0'"
    ]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=9)
        output = p.stdout.strip().split("\n")[-1]
        parts = output.split("|")
        if len(parts) >= 4 and parts[0] != "FAIL" and parts[0] != "000":
            http_code = parts[0]
            dns_time = float(parts[1]) * 1000 # ms
            conn_time = float(parts[2]) * 1000 # ms
            total_time = float(parts[3]) * 1000 # ms
            success = True
        else:
            http_code = parts[0] if parts else "ERR"
            dns_time, conn_time, total_time = 0, 0, 0
            success = False
    except Exception:
        http_code = "TIMEOUT"
        dns_time, conn_time, total_time = 0, 0, 0
        success = False

    return {
        "name": name,
        "domain": domain,
        "expected_route": expected,
        "actual_route": route_dec,
        "route_match": (route_dec == expected),
        "success": success,
        "http_code": http_code,
        "dns_time_ms": round(dns_time, 2),
        "conn_time_ms": round(conn_time, 2),
        "total_time_ms": round(total_time, 2),
    }

def run_speed_test():
    print("\n" + "="*70)
    print("[*] 开始进行大文件吞吐与下载速率基准测试...")
    
    # 1. 代理隧道大文件吞吐 (Cloudflare 10MB 测试文件)
    print("  --> [1/2] 测试隧道代理大文件下载 (Cloudflare CDN: 10MB)...")
    cmd_proxy = [
        "adb", "-s", DEVICE, "shell",
        "curl -s -m 20 -o /dev/null -w '%{size_download}|%{speed_download}|%{time_total}' https://speed.cloudflare.com/__down?bytes=10485760"
    ]
    try:
        p1 = subprocess.run(cmd_proxy, capture_output=True, text=True, timeout=25)
        out1 = p1.stdout.strip().split("|")
        if len(out1) == 3:
            size_mb = round(float(out1[0]) / (1024*1024), 2)
            speed_mbps = round(float(out1[1]) * 8 / 1_000_000, 2)
            time_sec = round(float(out1[2]), 2)
            print(f"      ✓ 代理下载成功: 大小 {size_mb} MB | 耗时 {time_sec}s | 平均速率: {speed_mbps} Mbps")
            proxy_speed = {"size_mb": size_mb, "speed_mbps": speed_mbps, "time_sec": time_sec, "success": True}
        else:
            print("      ✗ 代理下载异常:", p1.stdout)
            proxy_speed = {"success": False}
    except Exception as e:
        print(f"      ✗ 代理下载超时/失败: {e}")
        proxy_speed = {"success": False}

    # 2. 国内直连大文件吞吐 (华为开源镜像 / 阿里镜像 10MB 测试文件)
    print("  --> [2/2] 测试国内直连大文件下载 (华为云开源镜像: 10MB)...")
    cmd_direct = [
        "adb", "-s", DEVICE, "shell",
        "curl -s -m 20 -o /dev/null -w '%{size_download}|%{speed_download}|%{time_total}' https://repo.huaweicloud.com/harmonyos/compiler/ninja/1.9.0/linux/ninja.1.9.0.tar"
    ]
    try:
        p2 = subprocess.run(cmd_direct, capture_output=True, text=True, timeout=25)
        out2 = p2.stdout.strip().split("|")
        if len(out2) == 3:
            size_mb = round(float(out2[0]) / (1024*1024), 2)
            speed_mbps = round(float(out2[1]) * 8 / 1_000_000, 2)
            time_sec = round(float(out2[2]), 2)
            print(f"      ✓ 直连下载成功: 大小 {size_mb} MB | 耗时 {time_sec}s | 平均速率: {speed_mbps} Mbps")
            direct_speed = {"size_mb": size_mb, "speed_mbps": speed_mbps, "time_sec": time_sec, "success": True}
        else:
            print("      ✗ 直连下载异常:", p2.stdout)
            direct_speed = {"success": False}
    except Exception as e:
        print(f"      ✗ 直连下载超时/失败: {e}")
        direct_speed = {"success": False}

    return {"proxy_speed": proxy_speed, "direct_speed": direct_speed}

def main():
    print(f"[*] 开始在 Android 16 设备 ({DEVICE}) 上执行全方位连接性与压力吞吐测试...")
    
    snap_before = query_debug_snapshot()
    print(f"[*] 测试前系统快照: RSS = {snap_before.get('process_rss_kb', 0)//1024}MB, FDs = {snap_before.get('process_fd_count', 0)}, TCP Conns = {snap_before.get('active_tcp_connections', 0)}")

    all_targets = GLOBAL_SERVICES + DOMESTIC_SERVICES
    results = []

    print(f"\n[*] 正在批量并发测试 {len(all_targets)} 个国内外关键服务...")
    with ThreadPoolExecutor(max_workers=8) as executor:
        futures = {executor.submit(test_service_on_device, item): item for item in all_targets}
        count = 0
        for f in as_completed(futures):
            res = f.result()
            results.append(res)
            count += 1
            status_icon = "✓" if res["success"] else "✗"
            print(f"[{count:02d}/{len(all_targets)}] {status_icon} {res['name']:<12} ({res['domain']:<28}) -> HTTP {res['http_code']:<4} | 路由: {res['actual_route']:<6} | 响应: {res['total_time_ms']:>6.1f}ms")

    # 执行速度与吞吐测试
    speed_results = run_speed_test()

    snap_after = query_debug_snapshot()
    print(f"\n[*] 测试后系统快照: RSS = {snap_after.get('process_rss_kb', 0)//1024}MB, FDs = {snap_after.get('process_fd_count', 0)}, TCP Conns = {snap_after.get('active_tcp_connections', 0)}")

    global_res = [r for r in results if r["expected_route"] == "proxy"]
    domestic_res = [r for r in results if r["expected_route"] == "direct"]

    summary = {
        "device": DEVICE,
        "timestamp": time.strftime("%Y-%m-%d %H:%M:%S"),
        "total_services_tested": len(results),
        "global_services": {
            "total": len(global_res),
            "success": sum(1 for r in global_res if r["success"]),
            "avg_latency_ms": round(sum(r["total_time_ms"] for r in global_res if r["success"]) / max(1, sum(1 for r in global_res if r["success"])), 2),
            "details": global_res
        },
        "domestic_services": {
            "total": len(domestic_res),
            "success": sum(1 for r in domestic_res if r["success"]),
            "avg_latency_ms": round(sum(r["total_time_ms"] for r in domestic_res if r["success"]) / max(1, sum(1 for r in domestic_res if r["success"])), 2),
            "details": domestic_res
        },
        "speed_benchmark": speed_results,
        "diagnostics": {
            "before": snap_before,
            "after": snap_after
        }
    }

    with open("/tmp/stress_access_test_report.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    print("\n" + "="*70)
    print(f"[*] 综合访问测试全部完成！报告已生成至: /tmp/stress_access_test_report.json")
    print(f"    - 海外重点服务连通: {summary['global_services']['success']} / {summary['global_services']['total']} (平均延时: {summary['global_services']['avg_latency_ms']} ms)")
    print(f"    - 国内核心服务连通: {summary['domestic_services']['success']} / {summary['domestic_services']['total']} (平均延时: {summary['domestic_services']['avg_latency_ms']} ms)")
    print("="*70)

if __name__ == "__main__":
    main()
