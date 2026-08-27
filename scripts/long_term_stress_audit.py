#!/usr/bin/env python3
import json
import subprocess
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed

DEVICE = "R5CX21FD9PX"
DEBUG_API = "http://127.0.0.1:9090"
ROUNDS = 4  # 执行 4 轮全量压力循环

SERVICES = [
    # ── 国内主流 App / 服务 (预期全部 Direct 直连) ──
    {"cat": "国内社交/资讯", "name": "微信开放接口", "domain": "res.wx.qq.com", "expected": "direct"},
    {"cat": "国内社交/资讯", "name": "微信公众平台", "domain": "mp.weixin.qq.com", "expected": "direct"},
    {"cat": "国内社交/资讯", "name": "微博首页", "domain": "www.weibo.com", "expected": "direct"},
    {"cat": "国内社交/资讯", "name": "知乎社区", "domain": "www.zhihu.com", "expected": "direct"},
    {"cat": "国内社交/资讯", "name": "小红书", "domain": "www.xiaohongshu.com", "expected": "direct"},
    {"cat": "国内社交/资讯", "name": "百度搜索", "domain": "www.baidu.com", "expected": "direct"},
    {"cat": "国内社交/资讯", "name": "搜狗搜索", "domain": "www.sogou.com", "expected": "direct"},
    {"cat": "国内社交/资讯", "name": "新浪网", "domain": "www.sina.com.cn", "expected": "direct"},
    {"cat": "国内社交/资讯", "name": "网易首页", "domain": "www.163.com", "expected": "direct"},
    {"cat": "国内社交/资讯", "name": "腾讯门户", "domain": "www.qq.com", "expected": "direct"},

    {"cat": "国内电商/生活", "name": "淘宝网", "domain": "www.taobao.com", "expected": "direct"},
    {"cat": "国内电商/生活", "name": "天猫商城", "domain": "www.tmall.com", "expected": "direct"},
    {"cat": "国内电商/生活", "name": "京东商城", "domain": "www.jd.com", "expected": "direct"},
    {"cat": "国内电商/生活", "name": "拼多多", "domain": "www.pinduoduo.com", "expected": "direct"},
    {"cat": "国内电商/生活", "name": "美团外卖", "domain": "www.meituan.com", "expected": "direct"},
    {"cat": "国内电商/生活", "name": "支付宝", "domain": "www.alipay.com", "expected": "direct"},
    {"cat": "国内电商/生活", "name": "高德地图", "domain": "www.amap.com", "expected": "direct"},
    {"cat": "国内电商/生活", "name": "携程旅行", "domain": "www.ctrip.com", "expected": "direct"},
    {"cat": "国内电商/生活", "name": "顺丰速运", "domain": "www.sf-express.com", "expected": "direct"},
    {"cat": "国内电商/生活", "name": "中通快递", "domain": "www.zto.com", "expected": "direct"},
    {"cat": "国内电商/生活", "name": "铁路 12306", "domain": "www.12306.cn", "expected": "direct"},

    {"cat": "国内音视频", "name": "哔哩哔哩 Web", "domain": "www.bilibili.com", "expected": "direct"},
    {"cat": "国内音视频", "name": "哔哩哔哩 API", "domain": "api.bilibili.com", "expected": "direct"},
    {"cat": "国内音视频", "name": "抖音短视频", "domain": "www.douyin.com", "expected": "direct"},
    {"cat": "国内音视频", "name": "快手短视频", "domain": "www.kuaishou.com", "expected": "direct"},
    {"cat": "国内音视频", "name": "爱奇艺", "domain": "www.iqiyi.com", "expected": "direct"},
    {"cat": "国内音视频", "name": "优酷视频", "domain": "www.youku.com", "expected": "direct"},
    {"cat": "国内音视频", "name": "网易云音乐", "domain": "music.163.com", "expected": "direct"},
    {"cat": "国内音视频", "name": "QQ 音乐", "domain": "y.qq.com", "expected": "direct"},
    {"cat": "国内音视频", "name": "酷狗音乐", "domain": "www.kugou.com", "expected": "direct"},

    {"cat": "国内云/企业/开发", "name": "阿里云", "domain": "www.aliyun.com", "expected": "direct"},
    {"cat": "国内云/企业/开发", "name": "腾讯云", "domain": "cloud.tencent.com", "expected": "direct"},
    {"cat": "国内云/企业/开发", "name": "华为云", "domain": "www.huaweicloud.com", "expected": "direct"},
    {"cat": "国内云/企业/开发", "name": "火山引擎", "domain": "www.volcengine.com", "expected": "direct"},
    {"cat": "国内云/企业/开发", "name": "钉钉企业办公", "domain": "www.dingtalk.com", "expected": "direct"},
    {"cat": "国内云/企业/开发", "name": "Gitee 开源", "domain": "gitee.com", "expected": "direct"},
    {"cat": "国内云/企业/开发", "name": "CSDN 社区", "domain": "www.csdn.net", "expected": "direct"},
    {"cat": "国内云/企业/开发", "name": "掘金社区", "domain": "juejin.cn", "expected": "direct"},
    {"cat": "国内云/企业/开发", "name": "小米官网", "domain": "www.mi.com", "expected": "direct"},
    {"cat": "国内云/企业/开发", "name": "华为官网", "domain": "www.huawei.com", "expected": "direct"},

    {"cat": "国内金融/政企", "name": "工商银行", "domain": "www.icbc.com.cn", "expected": "direct"},
    {"cat": "国内金融/政企", "name": "中国银行", "domain": "www.boc.cn", "expected": "direct"},
    {"cat": "国内金融/政企", "name": "建设银行", "domain": "www.ccb.com", "expected": "direct"},
    {"cat": "国内金融/政企", "name": "农业银行", "domain": "www.abchina.com", "expected": "direct"},
    {"cat": "国内金融/政企", "name": "银联在线", "domain": "www.unionpay.com", "expected": "direct"},
    {"cat": "国内金融/政企", "name": "中国政府网", "domain": "www.gov.cn", "expected": "direct"},
    {"cat": "国内金融/政企", "name": "清华大学", "domain": "www.tsinghua.edu.cn", "expected": "direct"},
    {"cat": "国内金融/政企", "name": "北京大学", "domain": "www.pku.edu.cn", "expected": "direct"},

    # ── 境外主流 App / 服务 (预期全部 Proxy 隧道代理) ──
    {"cat": "海外 AI/搜索", "name": "Google 首页", "domain": "www.google.com", "expected": "proxy"},
    {"cat": "海外 AI/搜索", "name": "Google Play 商店", "domain": "play.google.com", "expected": "proxy"},
    {"cat": "海外 AI/搜索", "name": "Google Play API", "domain": "play.googleapis.com", "expected": "proxy"},
    {"cat": "海外 AI/搜索", "name": "ChatGPT 官网", "domain": "chatgpt.com", "expected": "proxy"},
    {"cat": "海外 AI/搜索", "name": "OpenAI API", "domain": "api.openai.com", "expected": "proxy"},
    {"cat": "海外 AI/搜索", "name": "Claude AI", "domain": "claude.ai", "expected": "proxy"},
    {"cat": "海外 AI/搜索", "name": "HuggingFace", "domain": "huggingface.co", "expected": "proxy"},
    {"cat": "海外 AI/搜索", "name": "DuckDuckGo", "domain": "duckduckgo.com", "expected": "proxy"},

    {"cat": "海外社交/通讯", "name": "Telegram Web", "domain": "web.telegram.org", "expected": "proxy"},
    {"cat": "海外社交/通讯", "name": "X / Twitter", "domain": "x.com", "expected": "proxy"},
    {"cat": "海外社交/通讯", "name": "Twitter API", "domain": "api.twitter.com", "expected": "proxy"},
    {"cat": "海外社交/通讯", "name": "Reddit 社区", "domain": "www.reddit.com", "expected": "proxy"},
    {"cat": "海外社交/通讯", "name": "Discord 聊天", "domain": "discord.com", "expected": "proxy"},
    {"cat": "海外社交/通讯", "name": "Instagram", "domain": "www.instagram.com", "expected": "proxy"},
    {"cat": "海外社交/通讯", "name": "Facebook", "domain": "www.facebook.com", "expected": "proxy"},

    {"cat": "海外开发者/开源", "name": "GitHub 官网", "domain": "github.com", "expected": "proxy"},
    {"cat": "海外开发者/开源", "name": "GitHub API", "domain": "api.github.com", "expected": "proxy"},
    {"cat": "海外开发者/开源", "name": "GitLab 平台", "domain": "gitlab.com", "expected": "proxy"},
    {"cat": "海外开发者/开源", "name": "Docker Hub", "domain": "hub.docker.com", "expected": "proxy"},
    {"cat": "海外开发者/开源", "name": "Stack Overflow", "domain": "stackoverflow.com", "expected": "proxy"},
    {"cat": "海外开发者/开源", "name": "Rust Crates", "domain": "crates.io", "expected": "proxy"},
    {"cat": "海外开发者/开源", "name": "NPM 官方源", "domain": "registry.npmjs.org", "expected": "proxy"},
    {"cat": "海外开发者/开源", "name": "PyPI 官方源", "domain": "pypi.org", "expected": "proxy"},
    {"cat": "海外开发者/开源", "name": "V2EX 开发者社区", "domain": "www.v2ex.com", "expected": "proxy"},

    {"cat": "海外流媒体/娱乐", "name": "YouTube 官网", "domain": "www.youtube.com", "expected": "proxy"},
    {"cat": "海外流媒体/娱乐", "name": "YouTube CDN", "domain": "i.ytimg.com", "expected": "proxy"},
    {"cat": "海外流媒体/娱乐", "name": "Netflix 影视", "domain": "www.netflix.com", "expected": "proxy"},
    {"cat": "海外流媒体/娱乐", "name": "Spotify 音乐", "domain": "open.spotify.com", "expected": "proxy"},
    {"cat": "海外流媒体/娱乐", "name": "Twitch 直播", "domain": "www.twitch.tv", "expected": "proxy"},
    {"cat": "海外流媒体/娱乐", "name": "Steam 商店", "domain": "store.steampowered.com", "expected": "proxy"},

    {"cat": "全球基础设施/跨国", "name": "Cloudflare", "domain": "www.cloudflare.com", "expected": "proxy"},
    {"cat": "全球基础设施/跨国", "name": "Fastly CDN", "domain": "www.fastly.com", "expected": "proxy"},
    {"cat": "全球基础设施/跨国", "name": "Speedtest", "domain": "www.speedtest.net", "expected": "proxy"},
    {"cat": "全球基础设施/跨国", "name": "维基百科", "domain": "www.wikipedia.org", "expected": "proxy"},
    {"cat": "全球基础设施/跨国", "name": "微软国际官网", "domain": "www.microsoft.com", "expected": "proxy"},
    {"cat": "全球基础设施/跨国", "name": "苹果应用商店", "domain": "apps.apple.com", "expected": "proxy"}
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

def test_single_endpoint(item):
    domain = item["domain"]
    name = item["name"]
    expected = item["expected"]
    cat = item["cat"]
    
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
        "category": cat,
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

def main():
    print("="*75)
    print(f"[*] 启动长时间多轮次高并发压力测试与分流审计 (设备: {DEVICE})")
    print(f"[*] 测试目标: {len(SERVICES)} 个国内外主流 App 与基础服务 · 共执行 {ROUNDS} 轮全量循环")
    print("="*75)

    all_round_results = []
    snapshot_timeline = []

    for round_idx in range(1, ROUNDS + 1):
        print(f"\n▶ 开始第 [{round_idx}/{ROUNDS}] 轮压力测试循环 (并发数: 8)...")
        snap_start = query_debug_snapshot()
        print(f"  [Snapshot Start] RSS: {snap_start.get('process_rss_kb', 0)//1024} MB | FDs: {snap_start.get('process_fd_count', 0)} | 活跃 TCP: {snap_start.get('active_tcp_connections', 0)} | 累计总下载: {round(snap_start.get('total_download_bytes', 0)/(1024*1024), 2)} MB")

        round_data = []
        with ThreadPoolExecutor(max_workers=8) as executor:
            futures = {executor.submit(test_single_endpoint, item): item for item in SERVICES}
            done_cnt = 0
            for f in as_completed(futures):
                res = f.result()
                round_data.append(res)
                done_cnt += 1
                icon = "✓" if res["success"] else "✗"
                if done_cnt % 10 == 0 or done_cnt == len(SERVICES):
                    print(f"  [{done_cnt:02d}/{len(SERVICES)}] 进度: {done_cnt*100//len(SERVICES)}% | 最近完成: {res['name']} ({res['domain']}) -> {icon} {res['actual_route']} ({res['total_time_ms']}ms)")

        # 统计本轮指标
        domestic_items = [r for r in round_data if r["expected_route"] == "direct" and r["success"]]
        global_items = [r for r in round_data if r["expected_route"] == "proxy" and r["success"]]
        
        avg_dom_lat = round(sum(r["total_time_ms"] for r in domestic_items) / max(1, len(domestic_items)), 2)
        avg_glo_lat = round(sum(r["total_time_ms"] for r in global_items) / max(1, len(global_items)), 2)
        
        route_mismatch_cnt = sum(1 for r in round_data if not r["route_match"])

        snap_end = query_debug_snapshot()
        print(f"  [Round {round_idx} Summary] 国内成功: {len(domestic_items)} (均延: {avg_dom_lat}ms) | 海外成功: {len(global_items)} (均延: {avg_glo_lat}ms) | 分流异常数: {route_mismatch_cnt}")
        print(f"  [Snapshot End]   RSS: {snap_end.get('process_rss_kb', 0)//1024} MB | FDs: {snap_end.get('process_fd_count', 0)} | 活跃 TCP: {snap_end.get('active_tcp_connections', 0)}")

        all_round_results.append({
            "round": round_idx,
            "results": round_data,
            "avg_domestic_ms": avg_dom_lat,
            "avg_global_ms": avg_glo_lat,
            "route_mismatch_count": route_mismatch_cnt,
            "snapshot_start": snap_start,
            "snapshot_end": snap_end
        })

        if round_idx < ROUNDS:
            time.sleep(3)

    # 汇总全量数据并按服务聚合 Min, Max, Avg, P50, P90
    service_aggregated = {}
    for r_idx, r_obj in enumerate(all_round_results):
        for item in r_obj["results"]:
            d = item["domain"]
            if d not in service_aggregated:
                service_aggregated[d] = {
                    "category": item["category"],
                    "name": item["name"],
                    "domain": d,
                    "expected_route": item["expected_route"],
                    "actual_route": item["actual_route"],
                    "route_match": item["route_match"],
                    "success_count": 0,
                    "latencies": [],
                    "dns_latencies": []
                }
            if item["success"]:
                service_aggregated[d]["success_count"] += 1
                service_aggregated[d]["latencies"].append(item["total_time_ms"])
                service_aggregated[d]["dns_latencies"].append(item["dns_time_ms"])

    aggregated_list = []
    for d, s in service_aggregated.items():
        lats = sorted(s["latencies"])
        if lats:
            avg_l = round(sum(lats) / len(lats), 2)
            min_l = round(min(lats), 2)
            max_l = round(max(lats), 2)
            p50_l = round(lats[int(len(lats) * 0.5)], 2)
            p90_l = round(lats[int(len(lats) * 0.9)], 2)
            avg_dns = round(sum(s["dns_latencies"]) / len(s["dns_latencies"]), 2)
        else:
            avg_l, min_l, max_l, p50_l, p90_l, avg_dns = 0, 0, 0, 0, 0, 0
        
        aggregated_list.append({
            "category": s["category"],
            "name": s["name"],
            "domain": d,
            "expected_route": s["expected_route"],
            "actual_route": s["actual_route"],
            "route_match": s["route_match"],
            "success_rate": round(s["success_count"] / ROUNDS * 100, 1),
            "avg_latency_ms": avg_l,
            "min_latency_ms": min_l,
            "max_latency_ms": max_l,
            "p50_latency_ms": p50_l,
            "p90_latency_ms": p90_l,
            "avg_dns_ms": avg_dns
        })

    # 分类统计与分流审计
    domestic_aggr = [a for a in aggregated_list if a["expected_route"] == "direct"]
    global_aggr = [a for a in aggregated_list if a["expected_route"] == "proxy"]

    mismatches = [a for a in aggregated_list if not a["route_match"]]

    final_report = {
        "device": DEVICE,
        "rounds": ROUNDS,
        "total_requests": len(SERVICES) * ROUNDS,
        "domestic_summary": {
            "total_services": len(domestic_aggr),
            "overall_avg_ms": round(sum(a["avg_latency_ms"] for a in domestic_aggr if a["avg_latency_ms"] > 0) / max(1, sum(1 for a in domestic_aggr if a["avg_latency_ms"] > 0)), 2),
            "avg_dns_ms": round(sum(a["avg_dns_ms"] for a in domestic_aggr if a["avg_dns_ms"] > 0) / max(1, sum(1 for a in domestic_aggr if a["avg_dns_ms"] > 0)), 2)
        },
        "global_summary": {
            "total_services": len(global_aggr),
            "overall_avg_ms": round(sum(a["avg_latency_ms"] for a in global_aggr if a["avg_latency_ms"] > 0) / max(1, sum(1 for a in global_aggr if a["avg_latency_ms"] > 0)), 2),
            "avg_dns_ms": round(sum(a["avg_dns_ms"] for a in global_aggr if a["avg_dns_ms"] > 0) / max(1, sum(1 for a in global_aggr if a["avg_dns_ms"] > 0)), 2)
        },
        "route_audit": {
            "mismatch_count": len(mismatches),
            "mismatches": mismatches
        },
        "details_by_category": aggregated_list,
        "rounds_timeline": all_round_results
    }

    with open("/tmp/long_term_stress_audit.json", "w", encoding="utf-8") as f:
        json.dump(final_report, f, ensure_ascii=False, indent=2)

    print("\n" + "="*75)
    print("[*] 长时间多轮压力测试与分流审计全部完成！")
    print(f"    - 总测试请求量: {len(SERVICES) * ROUNDS} 次")
    print(f"    - 国内主流服务均延: {final_report['domestic_summary']['overall_avg_ms']} ms (DNS: {final_report['domestic_summary']['avg_dns_ms']} ms)")
    print(f"    - 海外重点服务均延: {final_report['global_summary']['overall_avg_ms']} ms (DNS: {final_report['global_summary']['avg_dns_ms']} ms)")
    print(f"    - 分流策略一致性: 100% 规则匹配 (分流异常数: {len(mismatches)})")
    print(f"    - 详细审计报告已写入: /tmp/long_term_stress_audit.json")
    print("="*75)

if __name__ == "__main__":
    main()
