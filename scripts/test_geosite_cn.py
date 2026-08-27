#!/usr/bin/env python3
import json
import subprocess
import time
import urllib.request
import urllib.parse
from concurrent.futures import ThreadPoolExecutor, as_completed

DEVICE = "R5CX21FD9PX"
DEBUG_API = "http://127.0.0.1:9090"

# 精选涵盖各大类别的 100 个 geosite:cn 典型域名
DOMAINS = [
    # 1. 头部互联网/搜索门户
    "www.baidu.com", "m.baidu.com", "www.sogou.com", "www.so.com",
    "www.sina.com.cn", "www.sohu.com", "www.163.com", "www.qq.com",
    
    # 2. 阿里系 (电商/支付/生活)
    "www.taobao.com", "www.tmall.com", "www.alipay.com", "www.aliyun.com",
    "www.1688.com", "www.ele.me", "www.amap.com", "www.dingtalk.com",
    
    # 3. 腾讯系 (社交/游戏/云)
    "weixin.qq.com", "res.wx.qq.com", "v.qq.com", "cloud.tencent.com",
    "game.qq.com", "mp.weixin.qq.com", "qpic.cn", "gtimg.cn",
    
    # 4. 字节跳动/快手/社交/资讯
    "www.douyin.com", "www.toutiao.com", "www.kuaishou.com", "www.weibo.com",
    "www.zhihu.com", "www.xiaohongshu.com", "www.tieba.baidu.com", "www.douban.com",
    
    # 5. 视频/流媒体/音乐
    "www.bilibili.com", "api.bilibili.com", "www.iqiyi.com", "www.youku.com",
    "www.mgtv.com", "music.163.com", "y.qq.com", "www.kugou.com",
    
    # 6. 京东/拼多多/美团/本地生活
    "www.jd.com", "api.m.jd.com", "www.pinduoduo.com", "yangkeduo.com",
    "www.meituan.com", "api.meituan.com", "www.dianping.com", "www.ctrip.com",
    
    # 7. 手机终端厂商/云服务
    "www.mi.com", "api.account.xiaomi.com", "www.huawei.com", "cloud.huawei.com",
    "www.oppo.com", "www.vivo.com", "www.honor.com", "www.smartisan.com",
    
    # 8. 开发者/开源/技术社区
    "gitee.com", "www.csdn.net", "www.oschina.net", "www.v2ex.com",
    "juejin.cn", "www.segmentfault.com", "www.cnblogs.com", "www.infoq.cn",
    
    # 9. 国内公有云/CDN/基础设施
    "www.huaweicloud.com", "www.volcengine.com", "www.qiniu.com", "www.upyun.com",
    "www.ucloud.cn", "www.kingsoft.com", "www.baidubce.com", "www.cnnic.cn",
    
    # 10. 金融银行/政企高校
    "www.icbc.com.cn", "www.ccb.com", "www.boc.cn", "www.abchina.com",
    "www.unionpay.com", "www.gov.cn", "www.tsinghua.edu.cn", "www.pku.edu.cn",
    
    # 11. 外企在华特设 CDN/镜像 (geosite:cn 重点排查)
    "www.apple.com.cn", "apps.apple.com", "a1.mzstatic.com", "www.bing.com",
    "cn.bing.com", "www.microsoft.com", "store.steampowered.com", "steamcontent.com",
    "www.battlenet.com.cn", "update.googleapis.com", "www.gstatic.com", "fonts.googleapis.com",
    
    # 12. 快递/汽车/出行
    "www.sf-express.com", "www.zto.com", "www.12306.cn", "www.didiglobal.com"
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
            return data.get("decision", "unknown"), data.get("is_strict_cn_domain", False)
    except Exception:
        return "unknown", False

def test_domain_on_device(domain):
    decision, is_strict_cn = query_debug_route(domain)
    
    cmd = [
        "adb", "-s", DEVICE, "shell",
        f"curl -s -m 6 -o /dev/null -w '%{{http_code}}|%{{time_namelookup}}|%{{time_connect}}|%{{time_total}}' -I https://{domain} 2>/dev/null || "
        f"curl -s -m 6 -o /dev/null -w '%{{http_code}}|%{{time_namelookup}}|%{{time_connect}}|%{{time_total}}' -I http://{domain} 2>/dev/null || "
        "echo 'FAIL|0|0|0'"
    ]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=8)
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
        "domain": domain,
        "rule_decision": decision,
        "is_strict_cn": is_strict_cn,
        "success": success,
        "http_code": http_code,
        "dns_time_ms": round(dns_time, 2),
        "conn_time_ms": round(conn_time, 2),
        "total_time_ms": round(total_time, 2),
    }

def main():
    print(f"[*] 开始对 {len(DOMAINS)} 个 geosite:cn 核心域名在索尼设备 ({DEVICE}) 上进行批量连接与路由测试...")
    results = []
    
    with ThreadPoolExecutor(max_workers=8) as executor:
        futures = {executor.submit(test_domain_on_device, d): d for d in DOMAINS}
        count = 0
        for f in as_completed(futures):
            res = f.result()
            results.append(res)
            count += 1
            status_icon = "✓" if res["success"] else "✗"
            print(f"[{count:03d}/{len(DOMAINS)}] {status_icon} {res['domain']:<32} -> HTTP {res['http_code']:<4} | 路由: {res['rule_decision']:<6} | 总耗时: {res['total_time_ms']:>6.1f}ms (DNS: {res['dns_time_ms']:>4.1f}ms)")

    total = len(results)
    success_count = sum(1 for r in results if r["success"])
    fail_count = total - success_count
    
    proxy_routes = sum(1 for r in results if r["rule_decision"] == "proxy")
    direct_routes = sum(1 for r in results if r["rule_decision"] == "direct")
    block_routes = sum(1 for r in results if r["rule_decision"] == "block")

    # 成功请求的平均延迟统计
    success_items = [r for r in results if r["success"]]
    avg_dns = round(sum(r["dns_time_ms"] for r in success_items) / len(success_items), 2) if success_items else 0
    avg_conn = round(sum(r["conn_time_ms"] for r in success_items) / len(success_items), 2) if success_items else 0
    avg_total = round(sum(r["total_time_ms"] for r in success_items) / len(success_items), 2) if success_items else 0

    summary = {
        "total_domains": total,
        "successful_connections": success_count,
        "failed_connections": fail_count,
        "success_rate_percent": round(success_count / total * 100, 2),
        "performance_averages_ms": {
            "dns_lookup_time": avg_dns,
            "tcp_connect_time": avg_conn,
            "total_request_time": avg_total
        },
        "route_distribution": {
            "direct": direct_routes,
            "proxy": proxy_routes,
            "block": block_routes
        },
        "details": sorted(results, key=lambda x: (not x["success"], x["total_time_ms"]))
    }

    with open("/tmp/geosite_cn_test_report.json", "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)

    print("\n" + "="*65)
    print(f"[*] geosite:cn 批量测试完成！")
    print(f"    - 域名总数: {total}")
    print(f"    - 连通成功: {success_count} ({summary['success_rate_percent']}%)")
    print(f"    - 连通失败: {fail_count}")
    print(f"    - 路由分流: 直连 {direct_routes} 个 | 代理 {proxy_routes} 个 | 拦截 {block_routes} 个")
    print(f"    - 性能平均: DNS {avg_dns}ms | 建连 {avg_conn}ms | 总响应 {avg_total}ms")
    print(f"    - 详细数据已保存至: /tmp/geosite_cn_test_report.json")
    print("="*65)

if __name__ == "__main__":
    main()
